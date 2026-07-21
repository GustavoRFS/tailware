// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"path/filepath"
	"runtime/debug"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/internal/authepoch"
	"github.com/tailscale/tailscale-android/libtailscale/internal/readiness"
	"github.com/tailscale/tailscale-android/libtailscale/internal/startup"
	"tailscale.com/health"
	"tailscale.com/logpolicy"
	"tailscale.com/logtail"
	"tailscale.com/logtail/filch"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/clientmetric"
	"tailscale.com/util/syspolicy/rsop"
	"tailscale.com/util/syspolicy/setting"
)

const defaultMTU = 1280 // minimalMTU from wgengine/userspace.go

// Android's service watchdog performs its own higher-level recovery. Bound the
// native bootstrap below that window so LocalAPI callers and a queued VPN owner
// receive a concrete failure instead of waiting forever.
const nativeBackendStartupTimeout = startup.BackendAttemptTimeout
const nativeBackendStartupAttempts = startup.BackendAttempts
const nativeBackendReadinessTimeout = startup.AggregateReadinessTimeout

// Attempt IDs are process-global because gomobile Start can be called again on
// the same Android AppContext. A delayed callback from an abandoned App must
// never be mistaken for the newest backend attempt.
var nextNativeBackendAttemptID atomic.Int64

const (
	logPrefKey               = "privatelogid"
	loginMethodPrefKey       = "loginmethod"
	customLoginServerPrefKey = "customloginserver"
)

func newApp(dataDir, directFileRoot string, hardwareAttestationPref bool, appCtx AppContext) Application {
	a := &App{
		directFileRoot: directFileRoot,
		dataDir:        dataDir,
		appCtx:         appCtx,
		authentication: authepoch.New(),
	}
	beginVPNCommandAttempt()

	a.store = newStateStore(a.appCtx)
	a.policyStore = &syspolicyStore{a: a}
	netmon.RegisterInterfaceGetter(a.getInterfaces)
	rsop.RegisterStore("DeviceHandler", setting.DeviceScope, a.policyStore)

	hwAttestEnabled := appCtx.HardwareAttestationKeySupported() && hardwareAttestationPref
	if hwAttestEnabled {
		key.RegisterHardwareAttestationKeyFns(
			func() key.HardwareAttestationKey { return emptyHardwareAttestationKey(appCtx) },
			func() (key.HardwareAttestationKey, error) { return createHardwareAttestationKey(appCtx) },
		)
	} else {
		log.Printf("HardwareAttestationKey is not supported on this device")
	}
	go a.watchFileOpsChanges()

	firstAttempt := a.beginBackendAttempt(1)
	go a.runNativeBackendAttempts(firstAttempt, hwAttestEnabled)

	return a
}

type backendAttempt struct {
	number   int
	id       int64
	ctx      context.Context
	cancel   context.CancelFunc
	ready    *readiness.Readiness
	replaced chan struct{}
	report   sync.Once
}

func (a *App) runNativeBackendAttempts(
	firstAttempt *backendAttempt,
	hardwareAttestation bool,
) {
	for attemptNumber := 1; attemptNumber <= nativeBackendStartupAttempts; attemptNumber++ {
		attempt := firstAttempt
		if attemptNumber > 1 {
			// runNativeBackendAttempt does not return until the old backend and
			// every context-aware callback have stopped. Requeue commands only
			// after that fence so two consumers can never own the mailbox.
			vpnCommands.Retry()
			attempt = a.beginBackendAttempt(attemptNumber)
		}
		err, becameReady := a.runNativeBackendAttempt(attempt, hardwareAttestation)
		if err == nil {
			err = errors.New("native backend event loop stopped without an error")
		}
		log.Printf("native backend attempt %d failed: %v", attemptNumber, err)
		attempt.reportStartupError(a)
		failedService, hadService := detachVPNServiceAfterBackendAttempt()

		if !becameReady && attemptNumber < nativeBackendStartupAttempts {
			log.Printf(
				"native backend attempt %d cleaned up; starting one controlled retry",
				attemptNumber,
			)
			continue
		}
		if becameReady && hadService {
			reportRunningBackendFailure(failedService, err)
		}
		failPendingVPNCommands(err)
		return
	}
}

func (a *App) beginBackendAttempt(number int) *backendAttempt {
	ctx, cancel := context.WithCancel(context.Background())
	attempt := &backendAttempt{
		number:   number,
		id:       nextNativeBackendAttemptID.Add(1),
		ctx:      ctx,
		cancel:   cancel,
		ready:    readiness.New(),
		replaced: make(chan struct{}),
	}
	a.backendMu.Lock()
	previous := a.currentAttempt
	a.currentAttempt = attempt
	a.backend = nil
	a.localAPIHandler = nil
	if previous != nil {
		close(previous.replaced)
	}
	a.backendMu.Unlock()
	return attempt
}

func (a *App) runNativeBackendAttempt(
	attempt *backendAttempt,
	hardwareAttestation bool,
) (runErr error, becameReady bool) {
	timeoutErr := fmt.Errorf(
		"native backend did not become ready within %s",
		nativeBackendStartupTimeout,
	)
	timer := time.AfterFunc(nativeBackendStartupTimeout, func() {
		if attempt.ready.Complete(timeoutErr) {
			attempt.cancel()
			attempt.reportStartupError(a)
		}
	})
	defer timer.Stop()
	defer attempt.cancel()
	defer func() {
		if p := recover(); p != nil {
			runErr = fmt.Errorf("panic during native startup: %v", p)
			log.Printf("panic in runBackend %s: %s", p, debug.Stack())
			attempt.ready.Complete(runErr)
		}
		finished, startupErr := attempt.ready.Result()
		if !finished {
			if runErr == nil {
				runErr = errors.New("native backend stopped before publishing startup readiness")
			}
			attempt.ready.Complete(runErr)
			_, startupErr = attempt.ready.Result()
		}
		becameReady = startupErr == nil
		if !becameReady {
			runErr = startupErr
		}
	}()

	runErr = a.runBackend(attempt, hardwareAttestation)
	return runErr, false
}

func (attempt *backendAttempt) reportStartupError(a *App) {
	attempt.report.Do(func() { a.reportNativeBackendStartupError(attempt.id) })
}

func detachVPNServiceAfterBackendAttempt() (vpnServiceSnapshot, bool) {
	vpnService.mu.Lock()
	service := vpnServiceSnapshot{
		service:           vpnService.service,
		serviceID:         vpnService.serviceID,
		commandGeneration: vpnService.commandGeneration,
	}
	ok := vpnService.service != nil
	vpnService.service = nil
	vpnService.target = nil
	vpnService.mu.Unlock()
	netns.SetAndroidProtectFunc(nil)
	netns.SetAndroidBindToNetworkFunc(nil)
	if ok {
		// Keep the generation and command kind as a stale-command barrier. A
		// requeued equal-generation START still proceeds because service is nil.
		log.Printf(
			"detached VPN service=%q generation=%d from stopped native backend",
			service.serviceID,
			service.commandGeneration,
		)
	}
	return service, ok
}

func reportRunningBackendFailure(service vpnServiceSnapshot, err error) {
	go func() {
		defer func() {
			if recovered := recover(); recovered != nil {
				log.Printf(
					"panic reporting running native backend failure for generation=%d: %v\n%s",
					service.commandGeneration,
					recovered,
					debug.Stack(),
				)
			}
		}()
		if !service.isDesiredCommandCurrent() ||
			!service.service.HandleVpnServiceError(service.commandGeneration) {
			log.Printf(
				"running native backend failure was not claimed by service=%q generation=%d: %v",
				service.serviceID,
				service.commandGeneration,
				err,
			)
			return
		}
		service.service.UpdateVpnStatus(false, service.commandGeneration)
	}()
}

func (a *App) reportNativeBackendStartupError(attemptID int64) {
	defer func() {
		if recovered := recover(); recovered != nil {
			log.Printf(
				"panic reporting native backend startup failure to Android: %v\n%s",
				recovered,
				debug.Stack(),
			)
		}
	}()
	a.appCtx.OnNativeBackendStartupError(attemptID)
}

func (a *App) reportNativeBackendRecovered(attemptID int64) {
	defer func() {
		if recovered := recover(); recovered != nil {
			log.Printf(
				"panic reporting native backend recovery to Android: %v\n%s",
				recovered,
				debug.Stack(),
			)
		}
	}()
	a.appCtx.OnNativeBackendRecovered(attemptID)
}

// osVersion returns android.os.Build.VERSION.RELEASE. " [nogoogle]" is appended
// if Google Play services are not compiled in.
func (a *App) osVersion() string {
	version, err := a.appCtx.GetOSVersion()
	if err != nil {
		panic(err)
	}
	return version
}

// deviceName returns the user-set name if available, or MANUFACTURER + MODEL from
// android.os.Build.
func (a *App) deviceName() string {
	model, err := a.appCtx.GetDeviceName()
	if err != nil {
		panic(err)
	}
	return model
}

func (a *App) isChromeOS() bool {
	isChromeOS, err := a.appCtx.IsChromeOS()
	if err != nil {
		panic(err)
	}
	return isChromeOS
}

func (a *App) isClientLoggingEnabled() bool {
	isClientLoggingEnabled, err := a.appCtx.IsClientLoggingEnabled()
	if err != nil {
		panic(err)
	}
	return isClientLoggingEnabled
}

// SetupLogs sets up remote logging.
func (b *backend) setupLogs(
	logDir string,
	logID logid.PrivateID,
	logf logger.Logf,
	health *health.Tracker,
	enableUpload bool,
) {
	if b.netMon == nil {
		panic("netMon must be created prior to SetupLogs")
	}
	transport := logpolicy.NewLogtailTransport(logtail.DefaultHost, b.netMon, health, log.Printf)

	logcfg := logtail.Config{
		Collection:          logtail.CollectionNode,
		PrivateID:           logID,
		Stderr:              log.Writer(),
		MetricsDelta:        clientmetric.EncodeLogTailMetricsDelta,
		IncludeProcID:       true,
		IncludeProcSequence: true,
		HTTPC:               &http.Client{Transport: transport},
		CompressLogs:        true,
		// Start the logger disabled if the user opted out, so not even
		// the internal "logtail started" banner reaches the server. The
		// SetClientLoggingEnabled path flips this at runtime.
		Disabled: !enableUpload,
	}
	logcfg.FlushDelayFn = func() time.Duration { return 2 * time.Minute }

	filchOpts := filch.Options{
		ReplaceStderr: true,
	}

	var filchErr error
	if logDir != "" {
		logPath := filepath.Join(logDir, "ipn.log.")
		logcfg.Buffer, filchErr = filch.New(logPath, filchOpts)
	}

	b.logger = logtail.NewLogger(logcfg, logf)
	if !enableUpload {
		log.Printf("remote log upload disabled by user preference")
	}

	log.SetFlags(0)
	log.SetOutput(b.logger)

	log.Printf("goSetupLogs: success")

	if logDir == "" {
		log.Printf("SetupLogs: no logDir, storing logs in memory")
	}
	if filchErr != nil {
		log.Printf("SetupLogs: filch setup failed: %v", filchErr)
	}

	b.callbacks.Go(func(callbackCtx context.Context) {
		for {
			select {
			case logstr := <-onLog:
				b.logger.Logf("%s", logstr)
			case <-callbackCtx.Done():
				return
			}
		}
	})
}

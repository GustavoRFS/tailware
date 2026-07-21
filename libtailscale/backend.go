// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"runtime/debug"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/internal/authepoch"
	"github.com/tailscale/tailscale-android/libtailscale/internal/callbackgroup"
	"github.com/tailscale/tailscale-android/libtailscale/internal/cleanup"
	"github.com/tailscale/tailscale-android/libtailscale/internal/vpnauthorization"
	"tailscale.com/drive/driveimpl"
	_ "tailscale.com/feature/condregister"
	"tailscale.com/feature/taildrop"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnauth"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/localapi"
	"tailscale.com/logtail"
	"tailscale.com/net/dns"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/net/tsdial"
	"tailscale.com/paths"
	"tailscale.com/tsd"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/eventbus"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/netstack"
	"tailscale.com/wgengine/router"
)

type App struct {
	dataDir string

	// passes along SAF file information for the taildrop manager
	directFileRoot  string
	shareFileHelper ShareFileHelper

	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx AppContext

	store             *stateStore
	policyStore       *syspolicyStore
	logIDPublicAtomic atomic.Pointer[logid.PublicID]

	localAPIHandler http.Handler
	backend         *ipnlocal.LocalBackend
	currentAttempt  *backendAttempt
	backendMu       sync.RWMutex
	authentication  *authepoch.Tracker
	// authenticationMu serializes authentication boundaries with creation of
	// new logical notification subscriptions. NotificationManager.Stop uses
	// notificationMu instead so it can never deadlock with an entered callback
	// that an authentication boundary is fencing.
	authenticationMu     sync.Mutex
	notificationMu       sync.Mutex
	notificationManagers map[*notificationManager]struct{}

	// wantRunningMu protects the short-lived generation registry used by
	// WantRunning LocalAPI requests. The mutex is never held while a request is
	// waiting for backend readiness or executing ServeHTTP.
	wantRunningMu         sync.Mutex
	wantRunningGeneration int64
	wantRunningCtx        context.Context
	wantRunningCancel     context.CancelCauseFunc

	// logger is the logtail logger whose uploads follow the user's
	// IsClientLoggingEnabled preference. Populated once runBackend wires
	// up the backend; nil before then.
	logger atomic.Pointer[logtail.Logger]
}

func start(dataDir, directFileRoot string, hwAttestationPref bool, appCtx AppContext) Application {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in Start %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	initLogging(appCtx)
	// Set XDG_CACHE_HOME to make os.UserCacheDir work.
	if _, exists := os.LookupEnv("XDG_CACHE_HOME"); !exists {
		cachePath := filepath.Join(dataDir, "cache")
		os.Setenv("XDG_CACHE_HOME", cachePath)
	}
	// Set XDG_CONFIG_HOME to make os.UserConfigDir work.
	if _, exists := os.LookupEnv("XDG_CONFIG_HOME"); !exists {
		cfgPath := filepath.Join(dataDir, "config")
		os.Setenv("XDG_CONFIG_HOME", cfgPath)
	}
	// Set HOME to make os.UserHomeDir work.
	if _, exists := os.LookupEnv("HOME"); !exists {
		os.Setenv("HOME", dataDir)
	}

	return newApp(dataDir, directFileRoot, hwAttestationPref, appCtx)
}

type backend struct {
	engine      wgengine.Engine
	backend     *ipnlocal.LocalBackend
	sys         *tsd.System
	devices     *multiTUN
	settings    settingsFunc
	tunConfigMu sync.Mutex
	tunOps      sync.WaitGroup
	lastCfg     *router.Config
	lastDNSCfg  *dns.OSConfig
	netMon      *netmon.Monitor
	startResult <-chan error

	logIDPublic logid.PublicID
	logger      *logtail.Logger

	bus *eventbus.Bus

	// avoidEmptyDNS controls whether to use fallback nameservers
	// when no nameservers are provided by Tailscale.
	avoidEmptyDNS bool

	appCtx AppContext

	callbacks *callbackgroup.Group
	cleanup   *cleanup.Group
}

type settingsFunc func(context.Context, *router.Config, *dns.OSConfig) error

func (a *App) publishBackend(
	attempt *backendAttempt,
	lb *ipnlocal.LocalBackend,
	handler http.Handler,
) {
	a.backendMu.Lock()
	defer a.backendMu.Unlock()
	if a.currentAttempt != attempt {
		return
	}
	a.backend = lb
	a.localAPIHandler = handler
}

func (a *App) unpublishBackend(attempt *backendAttempt, b *backend) {
	a.backendMu.Lock()
	defer a.backendMu.Unlock()
	if a.currentAttempt != attempt || a.backend != b.backend {
		return
	}
	a.backend = nil
	a.localAPIHandler = nil
}

// waitForBackend returns a coherent backend/LocalAPI pair belonging to one
// successful native attempt. If a failed attempt is replaced while the caller
// waits, it transparently follows the replacement instead of observing fields
// from two different attempts.
func (a *App) waitForBackend(
	ctx context.Context,
) (*ipnlocal.LocalBackend, http.Handler, error) {
	for {
		a.backendMu.RLock()
		attempt := a.currentAttempt
		a.backendMu.RUnlock()
		if attempt == nil {
			return nil, nil, errors.New("native backend has not started")
		}
		if err := attempt.ready.Wait(ctx); err != nil {
			a.backendMu.RLock()
			replaced := a.currentAttempt != attempt
			replacement := attempt.replaced
			retryExpected := attempt.number < nativeBackendStartupAttempts
			a.backendMu.RUnlock()
			if replaced {
				continue
			}
			if retryExpected {
				// A failed pre-readiness attempt is replaced only after its
				// callbacks and resources have been fully fenced. Follow that
				// handoff instead of exposing the stale readiness error during
				// the small cleanup/reinitialization window.
				select {
				case <-replacement:
					continue
				case <-ctx.Done():
					return nil, nil, ctx.Err()
				}
			}
			return nil, nil, err
		}

		a.backendMu.RLock()
		if a.currentAttempt != attempt {
			a.backendMu.RUnlock()
			continue
		}
		lb := a.backend
		handler := a.localAPIHandler
		a.backendMu.RUnlock()
		if lb == nil || handler == nil {
			return nil, nil, errors.New("native backend became unavailable")
		}
		return lb, handler, nil
	}
}

func (b *backend) shutdown(a *App) {
	err := b.cleanup.Close(
		cleanup.Step{Name: "cancel native callbacks", Run: func() error {
			b.callbacks.Stop()
			return nil
		}},
		cleanup.Step{Name: "bring native TUN down", Run: func() error {
			b.devices.Down()
			return nil
		}},
		cleanup.Step{Name: "close active TUN sessions", Run: func() error {
			b.CloseTUNs()
			return nil
		}},
		cleanup.Step{Name: "clear Android protect callback", Run: func() error {
			netns.SetAndroidProtectFunc(nil)
			return nil
		}},
		cleanup.Step{Name: "clear Android network-binding callback", Run: func() error {
			netns.SetAndroidBindToNetworkFunc(nil)
			return nil
		}},
		cleanup.Step{Name: "shut down native backend core", Run: func() error {
			return b.shutdownCore()
		}},
		cleanup.Step{Name: "wait for native callbacks", Run: func() error {
			b.callbacks.Wait()
			return nil
		}},
		cleanup.Step{Name: "shut down native logger", Run: func() error {
			if b.logger == nil {
				return nil
			}
			a.logger.CompareAndSwap(b.logger, nil)
			defer restoreAndroidLogOutput(a.appCtx)
			loggerCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			return b.logger.Shutdown(loggerCtx)
		}},
		cleanup.Step{Name: "close native network monitor", Run: func() error {
			if b.netMon == nil {
				return nil
			}
			return b.netMon.Close()
		}},
	)
	if err != nil {
		log.Printf("native backend cleanup completed with errors: %v", err)
	}
}

func (b *backend) shutdownCore() error {
	var errs []error
	backendClosed := false
	if b.backend != nil {
		if err := callResourceCloser("LocalBackend.Shutdown", b.backend.Shutdown); err != nil {
			errs = append(errs, err)
		} else {
			backendClosed = true
		}
	}
	// LocalBackend owns the engine after successful construction. If its
	// shutdown panics, fall back to closing the engine directly. Before a
	// LocalBackend exists, the constructor retains that responsibility.
	if b.engine != nil && !backendClosed {
		if err := callResourceCloser("wgengine.Close", b.engine.Close); err != nil {
			errs = append(errs, err)
		}
	}
	// multiTUN.Close is idempotent. Calling it even after its engine owner has
	// shut down guarantees that a partial constructor cannot strand its run
	// loop or an Android ParcelFileDescriptor.
	if err := b.devices.Close(); err != nil {
		errs = append(errs, fmt.Errorf("multiTUN.Close: %w", err))
	}
	return errors.Join(errs...)
}

func callResourceCloser(name string, close func()) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("%s panicked: %v", name, recovered)
		}
	}()
	close()
	return nil
}

func (a *App) runBackend(attempt *backendAttempt, hardwareAttestation bool) error {
	ctx := attempt.ctx
	paths.AppSharedDir.Store(a.dataDir)
	hostinfo.SetOSVersion(a.osVersion())
	hostinfo.SetPackage(a.appCtx.GetInstallSource())
	deviceModel := a.deviceName()
	if a.isChromeOS() {
		deviceModel = "ChromeOS: " + deviceModel
	}
	hostinfo.SetDeviceModel(deviceModel)
	hostinfo.SetHostnameFn(func() (string, error) {
		return a.deviceName(), nil
	})

	type configPair struct {
		rcfg *router.Config
		dcfg *dns.OSConfig
	}
	configs := make(chan configPair, 1)
	configErrs := make(chan error, 1)
	b, err := a.newBackend(ctx, a.dataDir, a.appCtx, a.store, func(callbackCtx context.Context, rcfg *router.Config, dcfg *dns.OSConfig) error {
		if rcfg == nil {
			return nil
		}
		select {
		case configs <- configPair{rcfg, dcfg}:
		case <-callbackCtx.Done():
			return callbackCtx.Err()
		}
		select {
		case err := <-configErrs:
			return err
		case <-callbackCtx.Done():
			return callbackCtx.Err()
		}
	})
	if err != nil {
		return err
	}
	defer func() {
		a.unpublishBackend(attempt, b)
		b.shutdown(a)
	}()
	a.logIDPublicAtomic.Store(&b.logIDPublic)
	a.logger.Store(b.logger)
	if hardwareAttestation {
		b.backend.SetHardwareAttested()
	}

	hc := localapi.HandlerConfig{
		Actor:    ipnauth.Self,
		Backend:  b.backend,
		Logf:     log.Printf,
		LogID:    *a.logIDPublicAtomic.Load(),
		EventBus: b.bus,
	}
	h := localapi.NewHandler(hc)
	h.PermitRead = true
	h.PermitWrite = true
	a.publishBackend(attempt, b.backend, h)

	// Contrary to the documentation for VpnService.Builder.addDnsServer,
	// ChromeOS doesn't fall back to the underlying network nameservers if
	// we don't provide any.
	b.avoidEmptyDNS = a.isChromeOS()

	var (
		cfg   configPair
		state ipn.State
	)
	startResult := b.startResult
	startupDone := attempt.ready.Done()

	stateCh := make(chan ipn.State, 1)
	b.callbacks.Go(func(watchCtx context.Context) {
		b.backend.WatchNotifications(watchCtx, ipn.NotifyInitialPrefs|ipn.NotifyInitialState|ipn.NotifyNoNetMap, func() {}, func(notify *ipn.Notify) bool {
			if notify.State != nil {
				select {
				case stateCh <- *notify.State:
				case <-watchCtx.Done():
					return false
				}
			}
			return true
		})
	})
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-startResult:
			if err != nil {
				return fmt.Errorf("start LocalBackend: %w", err)
			}
			if !attempt.ready.Complete(nil) {
				_, err := attempt.ready.Result()
				if err == nil {
					err = errors.New("native startup result was already completed")
				}
				return err
			}
			vpnCommands.MarkReady()
			if attempt.number > 1 {
				a.reportNativeBackendRecovered(attempt.id)
			}
			startResult = nil
			startupDone = nil
		case <-startupDone:
			_, err := attempt.ready.Result()
			if err == nil {
				err = errors.New("native startup ended before LocalBackend became ready")
			}
			return err
		case s := <-stateCh:
			state = s
			service, serviceOK := currentVPNServiceSnapshot()
			if state >= ipn.Starting && serviceOK && b.isConfigNonNilAndDifferent(cfg.rcfg, cfg.dcfg) {
				// On state change, check if there are router or config changes requiring an update to VPNBuilder
				if err := b.updateTUN(cfg.rcfg, cfg.dcfg, service); err != nil {
					if errors.Is(err, errMultipleUsers) {
						// TODO: surface error to user
					}
					a.closeVpnService(err, b, service)
				}
			}
		case c := <-configs:
			cfg = c
			service, serviceOK := currentVPNServiceSnapshot()
			if !serviceOK || !b.isConfigNonNilAndDifferent(cfg.rcfg, cfg.dcfg) {
				select {
				case configErrs <- nil:
				case <-ctx.Done():
					return ctx.Err()
				}
				break
			}
			configErr := b.updateTUN(cfg.rcfg, cfg.dcfg, service)
			select {
			case configErrs <- configErr:
			case <-ctx.Done():
				return ctx.Err()
			}
		case <-vpnCommands.Wake():
			command, ok := vpnCommands.Pop()
			if !ok {
				continue
			}
			switch command.kind {
			case vpnServiceCommandStart:
				if !waitForVPNTeardown(ctx) {
					return ctx.Err()
				}
				serviceID := command.service.ID()
				desiredGeneration := command.service.CommandGeneration()
				if command.generation != desiredGeneration ||
					!vpnauthorization.IsCommandAuthorized(command.service, command.generation) {
					log.Printf(
						"onVPNRequested: ignoring unauthorized service=%q generation=%d; Android desires generation=%d",
						serviceID,
						command.generation,
						desiredGeneration,
					)
					continue
				}
				reservation, rejection := reserveVPNStart(command, b)
				if rejection != "" {
					log.Printf(
						"onVPNRequested: ignoring service=%q generation=%d: %s",
						serviceID,
						command.generation,
						rejection,
					)
					continue
				}

				if reservation.replacement && reservation.snapshot.isCurrent() {
					// A TUN established by a different Android VpnService must not
					// be handed to the replacement service, even when the config and
					// desired generation are unchanged.
					b.devices.Down()
					b.CloseTUNs()
				}
				// Do not call DebugRebind here. It is only a connectivity workaround and
				// performs the exact native network transition implicated by this P0.
				if !installVPNHooks(reservation) {
					log.Printf(
						"onVPNRequested: authorization expired before hook ownership service=%q generation=%d",
						serviceID,
						command.generation,
					)
					if teardown, created, accepted := beginVPNTeardown(command.service, command.generation, b); accepted && created {
						completeVPNTeardown(teardown)
					}
					continue
				}

				if state >= ipn.Starting && b.isConfigNonNilAndDifferent(cfg.rcfg, cfg.dcfg) {
					if err := b.updateTUN(cfg.rcfg, cfg.dcfg, reservation.snapshot); err != nil {
						a.closeVpnService(err, b, reservation.snapshot)
					}
				} else if state >= ipn.Starting && b.hasLastTUNConfig() {
					// A newer command or replacement Android service can take ownership
					// of an already-active tunnel without requiring a replacement TUN.
					// Confirm it for the new owner so Android can restore Connected UI.
					if reservation.snapshot.isCurrent() {
						reservation.snapshot.service.UpdateVpnStatus(true, reservation.snapshot.commandGeneration)
					}
				}
			case vpnServiceCommandDisconnect:
				serviceID := command.service.ID()
				desiredGeneration := command.service.CommandGeneration()
				if command.generation != desiredGeneration {
					log.Printf(
						"onDisconnect: ignoring service=%q generation=%d; Android desires generation=%d",
						serviceID,
						command.generation,
						desiredGeneration,
					)
					continue
				}
				teardown, created, accepted := beginVPNTeardown(command.service, command.generation, b)
				if !accepted {
					log.Printf(
						"onDisconnect: ignoring stale or mismatched service=%q generation=%d",
						serviceID,
						command.generation,
					)
					continue
				}
				if teardown == nil {
					reportNativeVPNTeardownComplete(command.service, command.generation)
					continue
				}
				if created {
					completeVPNTeardown(teardown)
				}
			default:
				log.Printf("ignoring unknown VPN service command kind %d", command.kind)
			}
		case i := <-onDNSConfigChanged:
			// TODO (barnstar): Consider using [dns.Manager.RecompileDNSConfig] here.
			// NetworkChanged injects a netmon event that has the side effect
			// regenerating the DNS config but have the means to do
			// that independently of userspace engine network changes which may
			// eliminate some unnecessary work.
			b.callbacks.Go(func(ctx context.Context) {
				if ctx.Err() == nil {
					b.NetworkChanged(i)
				}
			})
		}
	}
}

func (a *App) newBackend(
	ctx context.Context,
	dataDir string,
	appCtx AppContext,
	store *stateStore,
	settings settingsFunc,
) (result *backend, resultErr error) {

	sys := tsd.NewSystem()
	sys.Set(store)

	if pemData, err := appCtx.GetUserCACertsPEM(); err != nil {
		log.Printf("GetUserCACertsPEM: %v", err)
	} else if len(pemData) > 0 {
		pool, err := x509.SystemCertPool()
		if err != nil {
			log.Printf("x509.SystemCertPool: %v; using empty pool", err)
			pool = x509.NewCertPool()
		}
		if pool.AppendCertsFromPEM(pemData) {
			sys.ExtraRootCAs = pool
			log.Printf("loaded user CA certificates into ExtraRootCAs")
		} else {
			log.Printf("failed to parse any user CA certificates from PEM data")
		}
	}

	logf := logger.Logf(log.Printf)
	callbacks := callbackgroup.New(ctx)
	b := &backend{
		devices:   newTUNDevices(),
		settings:  settings,
		appCtx:    appCtx,
		bus:       sys.Bus.Get(),
		callbacks: callbacks,
		cleanup:   cleanup.NewGroup(),
	}
	complete := false
	defer func() {
		if recovered := recover(); recovered != nil {
			b.shutdown(a)
			panic(recovered)
		}
		if !complete {
			b.shutdown(a)
		}
	}()

	var logID logid.PrivateID
	logID.UnmarshalText([]byte("dead0000dead0000dead0000dead0000dead0000dead0000dead0000dead0000"))
	storedLogID, err := store.read(logPrefKey)
	// In all failure cases we ignore any errors and continue with the dead value above.
	if err != nil || storedLogID == nil {
		// Read failed or there was no previous log id.
		newLogID, err := logid.NewPrivateID()
		if err == nil {
			logID = newLogID
			enc, err := newLogID.MarshalText()
			if err == nil {
				store.write(logPrefKey, enc)
			}
		}
	} else {
		logID.UnmarshalText([]byte(storedLogID))
	}

	netMon, err := netmon.New(b.bus, logf)
	if err != nil {
		return nil, fmt.Errorf("netmon.New: %w", err)
	}
	b.netMon = netMon
	b.setupLogs(dataDir, logID, logf, sys.HealthTracker.Get(), a.isClientLoggingEnabled())
	dialer := new(tsdial.Dialer)
	vf := &VPNFacade{
		SetBoth:           b.setCfg,
		GetBaseConfigFunc: b.getDNSBaseConfig,
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:            b.devices,
		Router:         vf,
		DNS:            vf,
		ReconfigureVPN: vf.ReconfigureVPN,
		Dialer:         dialer,
		SetSubsystem:   sys.Set,
		NetMon:         b.netMon,
		HealthTracker:  sys.HealthTracker.Get(),
		Metrics:        sys.UserMetricsRegistry(),
		DriveForLocal:  driveimpl.NewFileSystemForLocal(logf),
		EventBus:       sys.Bus.Get(),
	})
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewUserspaceEngine: %v", err)
	}
	b.engine = engine
	sys.Set(engine)
	b.logIDPublic = logID.Public()
	ns, err := netstack.Create(logf, sys.Tun.Get(), engine, sys.MagicSock.Get(), dialer, sys.DNSManager.Get(), sys.ProxyMapper())
	if err != nil {
		return nil, fmt.Errorf("netstack.Create: %w", err)
	}
	sys.Set(ns)
	ns.ProcessLocalIPs = false // let Android kernel handle it; VpnBuilder sets this up
	ns.ProcessSubnets = true   // for Android-being-an-exit-node support
	sys.NetstackRouter.Set(true)
	if w, ok := sys.Tun.GetOK(); ok {
		w.Start()
	}
	lb, err := ipnlocal.NewLocalBackend(logf, logID.Public(), sys, 0)
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewLocalBackend: %v", err)
	}
	b.backend = lb
	if ext, ok := ipnlocal.GetExt[*taildrop.Extension](lb); ok {
		ext.SetFileOps(newAndroidFileOps(a.shareFileHelper))
	}
	if err := ns.Start(lb); err != nil {
		return nil, fmt.Errorf("startNetstack: %w", err)
	}
	if b.logger != nil {
		lb.SetLogFlusher(b.logger.StartFlush)
	}
	b.sys = sys
	startResult := make(chan error, 1)
	b.startResult = startResult
	if !b.callbacks.Go(func(context.Context) {
		var startErr error
		defer func() {
			if recovered := recover(); recovered != nil {
				startErr = fmt.Errorf("panic starting LocalBackend: %v\n%s", recovered, debug.Stack())
			}
			startResult <- startErr
		}()
		startErr = lb.Start(ipn.Options{})
	}) {
		startResult <- callbacks.Context().Err()
	}
	complete = true
	return b, nil
}

func (a *App) watchFileOpsChanges() {
	for {
		select {
		case helper := <-onShareFileHelper:
			log.Printf("Got ShareFileHelper")
			a.shareFileHelper = helper
		}
	}
}

func (b *backend) isConfigNonNilAndDifferent(rcfg *router.Config, dcfg *dns.OSConfig) bool {
	b.tunConfigMu.Lock()
	defer b.tunConfigMu.Unlock()
	if reflect.DeepEqual(rcfg, b.lastCfg) && reflect.DeepEqual(dcfg, b.lastDNSCfg) {
		b.logger.Logf("isConfigNonNilAndDifferent: no change to Routes or DNS, ignore")
		return false
	}
	return rcfg != nil
}

func (b *backend) hasLastTUNConfig() bool {
	b.tunConfigMu.Lock()
	defer b.tunConfigMu.Unlock()
	return b.lastCfg != nil
}

func (a *App) closeVpnService(err error, b *backend, service vpnServiceSnapshot) {
	if !service.isProcessedCommandCurrent() {
		vpnService.mu.Lock()
		processedServiceID := vpnService.serviceID
		processedGeneration := vpnService.commandGeneration
		vpnService.mu.Unlock()
		log.Printf(
			"VPN update failed for stale service=%q generation=%d (processed service=%q generation=%d); ignoring: %v",
			service.serviceID,
			service.commandGeneration,
			processedServiceID,
			processedGeneration,
			err,
		)
		return
	}
	if !service.service.HandleVpnServiceError(service.commandGeneration) {
		log.Printf(
			"VPN update failure was not claimed by Android for service=%q generation=%d; ignoring: %v",
			service.serviceID,
			service.commandGeneration,
			err,
		)
		return
	}
	log.Printf(
		"VPN update failed for claimed service=%q generation=%d: %v",
		service.serviceID,
		service.commandGeneration,
		err,
	)

	if b.devices.Down() {
		log.Printf("tunnel brought down on VPN service error: %v", err)
	}
	b.CloseTUNs()
	netns.SetAndroidProtectFunc(nil)
	netns.SetAndroidBindToNetworkFunc(nil)
	service.service.UpdateVpnStatus(false, service.commandGeneration)

	vpnService.mu.Lock()
	if service.isProcessedCommandCurrentLocked() {
		vpnService.service = nil
		vpnService.serviceID = service.serviceID
		vpnService.commandGeneration = service.commandGeneration
		vpnService.commandKind = vpnServiceCommandDisconnect
		vpnService.target = nil
	}
	vpnService.mu.Unlock()
}

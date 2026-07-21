// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"log"

	_ "golang.org/x/mobile/bind"
)

// Start starts the application, storing state in the given dataDir and using
// the given appCtx.
func Start(dataDir, directFileRoot string, hwAttestationPref bool, appCtx AppContext) Application {
	return start(dataDir, directFileRoot, hwAttestationPref, appCtx)
}

// AppContext provides a context within which the Application is running. This
// context is a hook into functionality that's implemented on the Java side.
type AppContext interface {
	// Log logs the given tag and logLine
	Log(tag, logLine string)

	// OnNativeBackendStartupError reports that a complete native backend
	// startup attempt failed. This callback is independent from IPNService
	// ownership, so Android can surface the failure even when no VPN start
	// command exists yet.
	OnNativeBackendStartupError(attemptID int64)

	// OnNativeBackendRecovered reports that a clean replacement attempt became
	// ready after an earlier startup failure. Android uses this to clear the
	// global error and replace notification subscriptions that ended with the
	// failed attempt.
	OnNativeBackendRecovered(attemptID int64)

	// EncryptToPref stores the given value to an encrypted preference at the
	// given key.
	EncryptToPref(key, value string) error

	// DecryptFromPref retrieves the given value from an encrypted preference
	// at the given key, or returns empty string if unset.
	DecryptFromPref(key string) (string, error)

	// GetStateStoreKeysJson retrieves all keys stored in the encrypted SharedPreferences,
	// strips off the "statestore-" prefix, and returns them as a JSON array.
	GetStateStoreKeysJSON() string

	// GetOSVersion gets the Android version.
	GetOSVersion() (string, error)

	// GetSDKInt returns the Android SDK_INT (android.os.Build.VERSION.SDK_INT).
	GetSDKInt() (int, error)

	// GetDeviceName gets the Android device's user-set name, or hardware model name as a fallback.
	GetDeviceName() (string, error)

	// GetInstallSource gets information about how the app was installed or updated.
	GetInstallSource() string

	// ShouldUseGoogleDNSFallback reports whether or not to use Google for DNS fallback.
	ShouldUseGoogleDNSFallback() bool

	// IsChromeOS reports whether we're on a ChromeOS device.
	IsChromeOS() (bool, error)

	// IsClientLoggingEnabled reports whether the user has enabled remote client logging.
	IsClientLoggingEnabled() (bool, error)

	// GetInterfacesAsJson gets a JSON representation of all network
	// interfaces.
	GetInterfacesAsJson() (string, error)

	// GetPlatformDNSConfig gets a string representation of the current DNS
	// configuration.
	GetPlatformDNSConfig() string

	// GetSyspolicyStringValue returns the current string value for the given system policy.
	GetSyspolicyStringValue(key string) (string, error)

	// GetSyspolicyBooleanValue returns whether the given system policy is enabled.
	GetSyspolicyBooleanValue(key string) (bool, error)

	// GetSyspolicyStringArrayValue returns the current string array value for the given system policy,
	// expressed as a JSON string.
	GetSyspolicyStringArrayJSONValue(key string) (string, error)

	// Methods used to implement key.HardwareAttestationKey using the Android
	// KeyStore.
	HardwareAttestationKeySupported() bool
	HardwareAttestationKeyCreate() (id string, err error)
	HardwareAttestationKeyRelease(id string) error
	HardwareAttestationKeyPublic(id string) (pub []byte, err error)
	HardwareAttestationKeySign(id string, data []byte) (sig []byte, err error)
	HardwareAttestationKeyLoad(id string) error

	BindSocketToNetwork(fd int32) bool

	// GetUserCACertsPEM returns PEM-encoded user-installed CA certificates
	// from the Android KeyStore, or empty bytes if none are installed.
	GetUserCACertsPEM() ([]byte, error)
}

// IPNService corresponds to our IPNService in Java.
type IPNService interface {
	// ID returns the unique ID of this instance of the IPNService. Every time
	// we start a new IPN service, it should have a new ID.
	ID() string

	// CommandGeneration returns the newest desired-state generation known to
	// Android. Go uses it to avoid letting a late error from an older command
	// overwrite or disconnect a newer user command.
	CommandGeneration() int64

	// IsCommandAuthorized reports whether commandGeneration still owns a live,
	// process-local Connect capability. Persisted desired state alone is never
	// sufficient authorization for native networking or TUN work.
	IsCommandAuthorized(commandGeneration int64) bool

	// HandleVpnServiceError atomically claims an error for commandGeneration
	// on Android. It returns true only when that exact generation still owns
	// the desired state and Android has accepted responsibility for reconciling
	// WantRunning and the service lifecycle. Implementations must not
	// synchronously call RequestVPN or ServiceDisconnect from this callback,
	// because it runs on the Go backend loop.
	HandleVpnServiceError(commandGeneration int64) bool

	// Protect protects socket identified by the given file descriptor from
	// being captured by the VPN. The return value indicates whether or not the
	// socket was successfully protected.
	Protect(fd int32) bool

	// BindSocketToNetwork binds a native socket only while this service's exact
	// command generation still owns the process-local Connect capability.
	BindSocketToNetwork(fd int32) bool

	// NewBuilder creates a new VPNServiceBuilder in preparation for starting
	// the Android VPN.
	NewBuilder() VPNServiceBuilder

	Close()

	DisconnectVPN()

	// UpdateVpnStatus reports the native tunnel state for the exact Android
	// desired-state generation that caused it. Android must ignore callbacks
	// from older generations so a late TUN teardown cannot overwrite a newer
	// user start.
	UpdateVpnStatus(status bool, commandGeneration int64)

	// NativeVpnTeardownComplete acknowledges that the accepted disconnect for
	// commandGeneration has brought the native device down, closed every owned
	// TUN descriptor, and removed the generation's protect/bind hooks. Android
	// must match it to the exact pending teardown owner; an older acknowledged
	// teardown may release its barrier but must not overwrite a newer successor.
	NativeVpnTeardownComplete(commandGeneration int64)
}

// VPNServiceBuilder corresponds to Android's VpnService.Builder.
type VPNServiceBuilder interface {
	SetMTU(int32) error
	AddDNSServer(string) error
	AddSearchDomain(string) error
	AddRoute(string, int32) error
	ExcludeRoute(string, int32) error
	AddAddress(string, int32) error
	Establish() (ParcelFileDescriptor, error)
}

// ParcelFileDescriptor corresponds to Android's ParcelFileDescriptor.
type ParcelFileDescriptor interface {
	Detach() (int32, error)
}

// Application encapsulates the running Tailscale Application. There is only a
// single instance of Application per Android application.
type Application interface {
	// BeginAuthentication starts a fresh native ownership epoch for one login
	// attempt. Authentication-sensitive notifications carry this value to
	// Android so a delayed callback cannot be attributed to a replacement
	// attempt.
	BeginAuthentication() int64

	// CancelInteractiveLogin invalidates authenticationEpoch, disconnects the
	// current control client, and waits for callbacks that already captured the
	// epoch to return. It does nothing and returns false if a newer epoch owns
	// authentication.
	CancelInteractiveLogin(authenticationEpoch int64) bool

	// FinishAuthentication invalidates a successfully completed epoch without
	// disconnecting the current control client.
	FinishAuthentication(authenticationEpoch int64) bool

	// AuthenticationSequence snapshots the newest explicit or backend-generated
	// authentication sequence. Zero is the valid baseline before any epoch has
	// begun. Cancellation and finish retire the active owner without advancing
	// this sequence.
	AuthenticationSequence() int64

	// CallLocalAPI provides a mechanism for calling Tailscale's HTTP localapi
	// without having to call over the network.
	CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error)

	// CallLocalAPIForAuthenticationEpoch executes a LocalAPI handler only while
	// authenticationEpoch is still the exact newest authentication sequence.
	// The sequence is fenced for the handler's entire execution. Despite the
	// parameter name, zero is a valid baseline snapshot for signed-in operations
	// that begin before any interactive authentication epoch.
	CallLocalAPIForAuthenticationEpoch(authenticationEpoch int64, timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error)

	// AdvanceWantRunningGeneration advances the native cancellation boundary
	// for WantRunning requests. It is monotonic and non-blocking: advancing it
	// cancels the prior generation's request context without waiting for its
	// LocalAPI handler to return.
	AdvanceWantRunningGeneration(commandGeneration int64) bool

	// CallLocalAPIForWantRunningGeneration executes a LocalAPI request under the
	// exact WantRunning generation and, unless authenticationSequence is -1,
	// the exact authentication sequence. Sequence zero is the valid baseline;
	// values below -1 are invalid. Neither ownership registry is locked while
	// ServeHTTP executes, and either boundary cancels the request context.
	CallLocalAPIForWantRunningGeneration(commandGeneration, authenticationSequence int64, timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error)

	// CallLocalAPIMultipart is like CallLocalAPI, but instead of a single body,
	// it accepts multiple FileParts that get encoded as multipart/form-data.
	CallLocalAPIMultipart(timeoutMillis int, method, endpoint string, parts FileParts) (LocalAPIResponse, error)

	// NotifyPolicyChanged notifies the backend about a changed MDM policy,
	// so it can re-read it via the [syspolicyHandler].
	NotifyPolicyChanged()

	// SetClientLoggingEnabled sets whether diagnostic logs are uploaded to
	// Tailscale's logging backend. Changes take effect immediately.
	SetClientLoggingEnabled(enabled bool)

	// WatchNotifications provides a logical subscription to ipn.Notify updates.
	// Its physical native watcher is transparently rotated at authentication
	// boundaries so a queued callback can never acquire a replacement epoch.
	// The returned NotificationManager stops the logical subscription.
	WatchNotifications(mask int, cb NotificationCallback) NotificationManager
}

// FileParts is an array of multiple FileParts.
type FileParts interface {
	Len() int32
	Get(int32) *FilePart
}

// FilePart is a multipart file that can be submitted via CallLocalAPIMultiPart.
type FilePart struct {
	ContentLength int64
	Filename      string
	Body          InputStream
	ContentType   string // optional MIME content type
}

// LocalAPIResponse is a response to a localapi call, analogous to an http.Response.
type LocalAPIResponse interface {
	StatusCode() int
	BodyBytes() ([]byte, error)
	BodyInputStream() InputStream
}

// NotificationCallback is callback for receiving ipn.Notify messages.
type NotificationCallback interface {
	OnNotify([]byte) error
}

// NotificationManager provides a mechanism for a notification watcher to stop
// watching notifications.
type NotificationManager interface {
	Stop()
}

// InputStream provides an adapter between Java's InputStream and Go's
// io.Reader.
type InputStream interface {
	Read() ([]byte, error)
	Close() error
}

// OutputStream provides an adapter between Java's OutputStream and Go's
// io.WriteCloser.
type OutputStream interface {
	Write([]byte) (int, error)
	Close() error
}

// ShareFileHelper corresponds to the Kotlin ShareFileHelper class
type ShareFileHelper interface {
	// OpenFileWriter creates or truncates a file named fileName at a given offset,
	// returning an OutputStream for writing. Returns an error if the file cannot be opened.
	OpenFileWriter(fileName string, offset int64) (stream OutputStream, err error)

	// GetFileURI returns the SAF URI string for the file named fileName,
	// or an error if the file cannot be resolved.
	GetFileURI(fileName string) (uri string, err error)

	// RenameFile renames the file at oldPath (a SAF URI) into the Taildrop directory,
	// giving it the new targetName. Returns the SAF URI of the renamed file, or an error.
	RenameFile(oldPath string, targetName string) (newURI string, err error)

	// ListFilesJSON returns a JSON-encoded list of filenames in the Taildrop directory
	// that end with the specified suffix. If the suffix is empty, it returns all files.
	// Returns an error if no matching files are found or the directory cannot be accessed.
	ListFilesJSON(suffix string) (json string, err error)

	// OpenFileReader opens the file with the given name (typically a .partial file)
	// and returns an InputStream for reading its contents.
	// Returns an error if the file cannot be opened.
	OpenFileReader(name string) (stream InputStream, err error)

	// DeleteFile deletes the file identified by the given SAF URI string.
	// Returns an error if the file could not be deleted.
	DeleteFile(uri string) error

	// GetFileInfo returns a JSON-encoded string containing metadata for fileName,
	// matching the fields of androidFileInfo (name, size, modTime).
	// Returns an error if the file does not exist or cannot be accessed.
	GetFileInfo(fileName string) (json string, err error)
}

// The below are global callbacks that allow the Java application to notify Go
// of various state changes.

func RequestVPN(service IPNService, commandGeneration int64) {
	submitVPNCommand(vpnServiceCommand{
		service:    service,
		generation: commandGeneration,
		kind:       vpnServiceCommandStart,
	})
}

func ServiceDisconnect(service IPNService, commandGeneration int64) {
	// Accept and publish the exact OFF barrier synchronously. Descriptor closure
	// remains asynchronous, so this JNI call is short, but a newer Connect can no
	// longer overtake a queued disconnect before native ownership is fenced.
	teardown, created, accepted := beginVPNTeardown(service, commandGeneration, nil)
	if !accepted {
		return
	}
	if teardown == nil {
		reportNativeVPNTeardownComplete(service, commandGeneration)
		return
	}
	if created {
		go completeVPNTeardown(teardown)
	}
}

// ForceVPNTeardown installs an exact-generation fail-closed barrier and
// schedules native teardown without waiting on the backend command loop. It
// returns true when the request was accepted, was already in flight, or had
// already completed for this exact service/generation. Completion is reported
// through IPNService.NativeVpnTeardownComplete.
func ForceVPNTeardown(service IPNService, commandGeneration int64) bool {
	return forceVPNTeardown(service, commandGeneration)
}

func SendLog(logstr []byte) {
	select {
	case onLog <- string(logstr):
		// Successfully sent log
	default:
		// Channel is full, log not sent
		log.Printf("Log %v not sent", logstr) // missing argument in original code
	}
}

func SetShareFileHelper(fileHelper ShareFileHelper) {
	// Drain the channel if there's an old value.
	select {
	case <-onShareFileHelper:
	default:
		// Channel was already empty.
	}
	select {
	case onShareFileHelper <- fileHelper:
	default:
		// In the unlikely case the channel is still full, drain it and try again.
		<-onShareFileHelper
		onShareFileHelper <- fileHelper
	}
}

// hal/aidl/vendor/oscam/cas/IOscamCasListener.aidl
package vendor.oscam.cas;

/**
 * Asynchronous event callbacks dispatched to the Android TV framework.
 */
@VintfStability
interface IOscamCasListener {
    /**
     * Notifies the framework that a new Control Word has been resolved and injected.
     */
    void onControlWordReady(int sessionHandle, in byte[] controlWord);

    /**
     * Notifies the framework about session errors, timeouts, or disconnection events.
     */
    void onSessionError(int sessionHandle, int errorCode);
}

// hal/aidl/vendor/oscam/cas/IOscamCas.aidl
package vendor.oscam.cas;

/**
 * CAS session interface for processing ECM/EMM packets
 * and resolving Control Words (CW).
 */
@VintfStability
interface IOscamCas {
    /**
     * Opens a new CAS session and returns its unique session handle.
     */
    int openSession();

    /**
     * Closes the CAS session, releasing allocated demux filters and slots.
     */
    void closeSession(int sessionHandle);

    /**
     * Processes an ECM section/packet received from the Tuner HAL.
     */
    void processEcm(int sessionHandle, in byte[] ecmData);

    /**
     * Processes EMM packets for entitlement and subscription card updates.
     */
    void processEmm(in byte[] emmData);

    /**
     * Returns the resolved Control Word for the specified session.
     */
    byte[] getControlWord(int sessionHandle);
}

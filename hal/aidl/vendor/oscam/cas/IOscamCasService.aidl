// hal/aidl/vendor/oscam/cas/IOscamCasService.aidl
package vendor.oscam.cas;

import vendor.oscam.cas.IOscamCas;
import vendor.oscam.cas.IOscamCasListener;

/**
 * Root AIDL HAL service interface for the OSCam CAS plugin on Android TV.
 */
@VintfStability
interface IOscamCasService {
    /**
     * Checks whether the specified CA_system_id is supported.
     */
    boolean isSystemIdSupported(int caSystemId);

    /**
     * Instantiates a CAS plugin session for the given CAID.
     */
    IOscamCas createPlugin(int caSystemId, IOscamCasListener listener);
}

# build/device.mk
#
# Vendor device makefile snippet to include the Android OSCam CAS Bridge in AOSP builds.
# Include this file in your device makefile (e.g. device/tcl/beyondtv/device.mk):
#
#   $(call inherit-product, vendor/oscam/cas/build/device.mk)
#

# 1. Vendor HAL Service Binary and Libraries
PRODUCT_PACKAGES += \
    vendor.oscam.cas-service \
    liboscam_bridge \
    liboscam_chipset \
    liboscam_jni

# 2. VINTF Manifest Fragment
PRODUCT_COPY_FILES += \
    vendor/oscam/cas/hal/manifest/android.hardware.cas.xml:$(TARGET_COPY_OUT_VENDOR)/etc/vintf/manifest/android.hardware.cas.xml

# 3. MediaCas Configuration for System CAIDs
PRODUCT_COPY_FILES += \
    vendor/oscam/cas/hal/manifest/cas_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/cas_config.xml

# 4. SELinux Policy Files
BOARD_VENDOR_SEPOLICY_DIRS += \
    vendor/oscam/cas/hal/sepolicy


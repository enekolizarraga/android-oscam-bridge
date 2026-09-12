package com.lizarragaeus.oscambridge

/**
 * Intelligent Conditional Access System (CAS) Detector.
 * Identifies broadcast encryption standards (Nagravision, Viaccess, NDS VideoGuard,
 * Seca / Mediaguard, Conax, Irdeto, Cryptoworks, etc.) from DVB CAIDs.
 */
object CasSystemDetector {

    data class CasInfo(
        val caid: Int,
        val systemName: String,
        val shortCode: String,
        val badgeColor: String,
        val description: String,
        val isEncrypted: Boolean
    )

    fun detect(caid: Int): CasInfo {
        if (caid == 0) {
            return CasInfo(
                caid = 0,
                systemName = "Free-To-Air",
                shortCode = "FTA",
                badgeColor = "#3B82F6",
                description = "En abierto / Clear Broadcast (Sin cifrado)",
                isEncrypted = false
            )
        }

        val highByte = (caid ushr 8) and 0xFF
        return when (highByte) {
            0x18 -> {
                val detail = when (caid) {
                    0x1810 -> "Nagravision (Movistar+ España Astra 19.2°E / Hispasat 30°W)"
                    0x1830 -> "Nagravision HD01 (HD+ Deutschland Astra 19.2°E)"
                    0x1843 -> "Nagravision HD02 (HD+ Deutschland Astra 19.2°E)"
                    0x1860 -> "Nagravision HD03 (HD+ Deutschland Astra 19.2°E)"
                    0x186A -> "Nagravision HD04 (HD+ Deutschland Astra 19.2°E)"
                    0x183E -> "Nagravision (Tivùsat Italia Hotbird 13°E)"
                    0x1856 -> "Nagravision (Tivùsat 4K Italia Hotbird 13°E)"
                    0x1802 -> "Nagravision (MEO / NOS Portugal Hispasat 30°W)"
                    0x1803 -> "Nagravision (Polsat Box Polonia Hotbird 13°E)"
                    0x1884 -> "Nagravision (Digi TV / Tivùsat)"
                    0x1801 -> "Nagravision 2/3 (TDT Premium / Mediaset)"
                    else -> "Nagravision (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "Nagravision", "NAGRA", "#F59E0B", detail, true)
            }
            0x05 -> {
                val detail = when (caid) {
                    0x0500 -> "Viaccess (Fransat 5°W, SRG SSR Suiza 13°E, BIS TV, Orange TV)"
                    else -> "Viaccess (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "Viaccess", "VIACCESS", "#10B981", detail, true)
            }
            0x01 -> {
                val detail = when (caid) {
                    0x0100 -> "Seca / Mediaguard (Canal+ Francia / España Astra 19.2°E, Telesat)"
                    else -> "Seca / Mediaguard (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "Seca / Mediaguard", "SECA", "#8B5CF6", detail, true)
            }
            0x09 -> {
                val detail = when (caid) {
                    0x098C -> "NDS VideoGuard (Sky Deutschland V14 Astra 19.2°E)"
                    0x09C4 -> "NDS VideoGuard (Sky Deutschland V13 Astra 19.2°E)"
                    0x098D -> "NDS VideoGuard (Sky Deutschland V15 Astra 19.2°E)"
                    0x09CD -> "NDS VideoGuard (Sky Italia Hotbird 13°E)"
                    0x093B -> "NDS VideoGuard (Sky Italia Hotbird 13°E)"
                    0x0963 -> "NDS VideoGuard (Sky UK Astra 28.2°E)"
                    0x092B -> "NDS VideoGuard (D-Smart Türksat 42°E)"
                    else -> "NDS VideoGuard (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "NDS VideoGuard", "NDS", "#EC4899", detail, true)
            }
            0x0B -> {
                val detail = when (caid) {
                    0x0B00 -> "Conax (Canal Digital Nordic Thor 0.8°W, Telewizja na kartę)"
                    0x0B01 -> "Conax (Platforma Canal+ / nc+ Polonia 13°E)"
                    0x0B02 -> "Conax (Focus Sat Thor 0.8°W)"
                    else -> "Conax (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "Conax", "CONAX", "#06B6D4", detail, true)
            }
            0x06 -> {
                val detail = when (caid) {
                    0x0604 -> "Irdeto 2 (Nova Grecia Hotbird 13°E, Ziggo)"
                    0x0624 -> "Irdeto 2 (Skylink Astra 23.5°E)"
                    0x0648 -> "Irdeto (ORF Digital Austria Astra 19.2°E)"
                    0x0627 -> "Irdeto (Digitürk Eutelsat 7°E)"
                    else -> "Irdeto (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "Irdeto", "IRDETO", "#EF4444", detail, true)
            }
            0x0D -> {
                val detail = when (caid) {
                    0x0D95 -> "Cryptoworks (ORF Digital Austria Astra 19.2°E)"
                    0x0D96 -> "Cryptoworks (Skylink Czech / Slovakia)"
                    0x0D98 -> "Cryptoworks (ORF Digital Austria)"
                    0x0D05 -> "Cryptoworks legacy (ORF Digital)"
                    else -> "Cryptoworks (CAID 0x%04X)".format(caid)
                }
                CasInfo(caid, "Cryptoworks", "CW", "#F97316", detail, true)
            }
            0x17 -> CasInfo(caid, "Betacrypt", "BETA", "#6366F1", "Betacrypt (d-box / Premiere legacy)", true)
            0x0E -> CasInfo(caid, "PowerVu", "PVU", "#14B8A6", "PowerVu (AFN, Discovery Network feeds)", true)
            0x26 -> CasInfo(caid, "BISS", "BISS", "#84CC16", "BISS (Basic Interoperable Scrambling System feeds)", true)
            0x4A -> CasInfo(caid, "DRE-Crypt", "DRE", "#A855F7", "DRE-Crypt (Tricolor TV / Tongshi)", true)
            0x56 -> CasInfo(caid, "Verimatrix", "VMX", "#E11D48", "Verimatrix VCAS Broadcast", true)
            else -> CasInfo(caid, "CAS 0x%04X".format(caid), "CAS", "#94A3B8", "Conditional Access ID 0x%04X".format(caid), true)
        }
    }

    fun getBadgeHtml(caid: Int): String {
        val info = detect(caid)
        val bg = info.badgeColor + "22"
        return """<span style="background:$bg; border:1px solid ${info.badgeColor}; color:${info.badgeColor}; padding:2px 7px; border-radius:4px; font-weight:700; font-size:11px; white-space:nowrap;" title="${info.description}">${info.shortCode}</span>"""
    }

    fun getAllPresets(): List<Pair<String, Int>> = listOf(
        "Nagravision - Movistar+ España (0x1810)" to 0x1810,
        "Nagravision - HD+ Germany HD01 (0x1830)" to 0x1830,
        "Nagravision - HD+ Germany HD02 (0x1843)" to 0x1843,
        "Nagravision - Tivùsat Italia (0x183E)" to 0x183E,
        "Nagravision - MEO Portugal (0x1802)" to 0x1802,
        "Nagravision - Polsat Box (0x1803)" to 0x1803,
        "Viaccess - Fransat / SRG Suiza (0x0500)" to 0x0500,
        "Seca - Canal+ Francia / España (0x0100)" to 0x0100,
        "NDS - Sky Deutschland (0x098C)" to 0x098C,
        "NDS - Sky Italia (0x09CD)" to 0x09CD,
        "NDS - Sky UK (0x0963)" to 0x0963,
        "Conax - Canal Digital Nordic (0x0B00)" to 0x0B00,
        "Irdeto - Nova Grecia (0x0604)" to 0x0604,
        "Cryptoworks - ORF Digital (0x0D95)" to 0x0D95,
        "Free-To-Air - En abierto (0x0000)" to 0x0000
    )
}


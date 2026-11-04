package fr.cgaspard.lumixphotos

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Une valeur possible d'un réglage (commande `setsetting&type=…&value=…[&value2=…]`). */
data class SettingOption(val itemId: String, val label: String, val value: String, val value2: String? = null)

/** Un réglage de l'appareil, tel que décrit par son menu (`getinfo&type=allmenu`). */
data class CameraSetting(
    val id: String,
    val title: String,
    /** Paramètre `type` de setsetting / getsetting. */
    val type: String,
    val section: String,
    val options: List<SettingOption>,
)

/**
 * Lecture des menus que l'appareil décrit lui-même : réglages disponibles, valeurs possibles et
 * libellés (en français quand l'appareil les fournit, sinon en anglais).
 */
object CameraMenu {

    private val SECTIONS = linkedMapOf(
        "qmenu2" to "Prise de vue",
        "photosettings" to "Photo et vidéo",
        "mainmenu" to "Commande à distance",
    )

    fun parse(allMenuXml: String, language: String = "fr"): List<CameraSetting> {
        val root = parseXml(allMenuXml)
        val titles = HashMap<String, String>()
        // Anglais d'abord (complet), puis la langue voulue par-dessus
        for (code in listOf("en", language)) {
            elements(root, "language").filter { it.getAttribute("code") == code }.forEach { lang ->
                elements(lang, "title").forEach { titles[it.getAttribute("id")] = it.textContent.trim() }
            }
        }
        val menuset = elements(root, "menuset").firstOrNull() ?: return emptyList()
        val settings = mutableListOf<CameraSetting>()
        for ((tag, sectionTitle) in SECTIONS) {
            val section = childElements(menuset).firstOrNull { it.tagName == tag } ?: continue
            collect(section, sectionTitle, titles, settings)
        }
        // Un même réglage peut figurer dans plusieurs sections : on garde la première occurrence
        return settings.distinctBy { it.id }
    }

    /**
     * On descend dans les sous-menus ; un autre élément porteur de valeurs devient un réglage.
     * Les éléments sans valeur (aide, nom de réseau…) sont ignorés.
     */
    private fun collect(node: Element, section: String, titles: Map<String, String>, out: MutableList<CameraSetting>) {
        for (child in childElements(node)) {
            if (child.tagName != "item" || child.getAttribute("func_type") == "submenu") {
                collect(child, section, titles, out)
                continue
            }
            val options = elements(child, "item")
                .filter { it.getAttribute("cmd_mode") == "setsetting" && it.getAttribute("cmd_value") != "__value__" }
                .map { option ->
                    SettingOption(
                        itemId = option.getAttribute("id"),
                        label = titles[option.getAttribute("title_id")] ?: option.getAttribute("cmd_value"),
                        value = option.getAttribute("cmd_value"),
                        // « __value__ » : paramètre réglé ailleurs (ex. contraste d'un style photo), pas une valeur
                        value2 = option.getAttribute("cmd_value2").takeUnless { it.isEmpty() || it == "__value__" },
                    )
                }
            if (options.size >= 2) {
                out += CameraSetting(
                    id = child.getAttribute("id"),
                    title = titles[child.getAttribute("title_id")] ?: child.getAttribute("title_id"),
                    type = elements(child, "item").first { it.getAttribute("cmd_mode") == "setsetting" }.getAttribute("cmd_type"),
                    section = section,
                    // Le premier élément d'un groupe « rafale », « retardateur »… répète souvent le titre
                    options = options.filterNot { it.value2 == null && options.any { o -> o.value == it.value && o.value2 != null } },
                )
            }
        }
    }

    /** Identifiants des valeurs utilisables dans le mode actuel (`getinfo&type=curmenu`). */
    fun enabledItems(curMenuXml: String): Set<String> {
        val root = parseXml(curMenuXml)
        return elements(root, "item").filter { it.getAttribute("enable") == "yes" }.mapTo(HashSet()) { it.getAttribute("id") }
    }

    /**
     * Valeur actuelle renvoyée par `getsetting` (attribut du même nom que le type). Lue sans
     * analyseur XML : pour « 4k_photo », l'appareil produit un nom d'attribut invalide en XML.
     */
    fun currentValue(getSettingXml: String, type: String): Pair<String, String?>? {
        fun attribute(name: String) = Regex("""\s${Regex.escape(name)}="([^"]*)"""").find(getSettingXml)?.groupValues?.get(1)
        val value = attribute(type)?.ifEmpty { null } ?: return null
        return value to attribute("${type}2")?.ifEmpty { null }
    }

    // --- Ouverture et vitesse : valeurs APEX en 1/256, bornes données par getinfo&type=lens ---

    private val F_NUMBERS = listOf(1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0)
    private val SHUTTER_DENOMINATORS = listOf(
        16000, 12800, 10000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1300, 1000, 800, 640, 500, 400, 320,
        250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3,
    )
    private val LONG_EXPOSURES = listOf(
        0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0,
    )

    /** Pose la plus longue (60 s) en 1/256 d'IL. */
    private const val SLOWEST_SHUTTER_APEX = -1536

    /** Bornes (Av min, Av max, Tv min, Tv max) en 1/256 d'IL, ex. « ok,1536/256,761/256,3584/256,-1536/256,… ». */
    data class LensLimits(val minAv: Int, val maxAv: Int, val minTv: Int, val maxTv: Int)

    fun lensLimits(lens: String): LensLimits? {
        val parts = lens.split(',')
        if (parts.firstOrNull() != "ok" || parts.size < 5) return null
        fun apex(s: String) = s.substringBefore('/').toIntOrNull()
        val maxAv = apex(parts[1]) ?: return null
        val minAv = apex(parts[2]) ?: return null
        val maxTv = apex(parts[3]) ?: return null
        // En mode M, l'appareil renvoie ici une valeur sentinelle (16384/256) : on prend alors 60 s
        val minTv = apex(parts[4])?.takeIf { it < maxTv } ?: SLOWEST_SHUTTER_APEX
        return LensLimits(minAv, maxAv, minTv, maxTv)
    }

    /** Valeurs au tiers d'IL entre deux bornes APEX (en 1/256). */
    private fun thirdStops(from: Int, to: Int): List<Int> {
        val start = (from * 3.0 / 256).let { kotlin.math.ceil(it - 0.15).toInt() }
        val end = (to * 3.0 / 256).let { kotlin.math.floor(it + 0.15).toInt() }
        return (start..end).map { (it * 256.0 / 3).roundToInt() }
    }

    fun apertureLabel(apex: Int): String {
        val f = 2.0.pow(apex / 256.0 / 2)
        val nominal = F_NUMBERS.minBy { abs(it - f) }
        return "f/" + if (nominal % 1.0 == 0.0) nominal.toInt().toString() else nominal.toString().replace('.', ',')
    }

    fun shutterLabel(apex: Int): String {
        val seconds = 2.0.pow(-apex / 256.0)
        return if (seconds < 0.35) {
            "1/" + SHUTTER_DENOMINATORS.minBy { abs(1.0 / it - seconds) }
        } else {
            val s = LONG_EXPOSURES.minBy { abs(it - seconds) }
            (if (s % 1.0 == 0.0) s.toInt().toString() else s.toString().replace('.', ',')) + " s"
        }
    }

    fun apertureSetting(limits: LensLimits) = CameraSetting(
        id = "aperture", title = "Ouverture", type = "focal", section = "Prise de vue",
        options = thirdStops(limits.minAv, limits.maxAv).map { SettingOption("aperture_$it", apertureLabel(it), "$it/256") },
    )

    fun shutterSetting(limits: LensLimits) = CameraSetting(
        id = "shutter", title = "Vitesse", type = "shtrspeed", section = "Prise de vue",
        // Des plus rapides aux plus longues, comme sur l'appareil
        options = thirdStops(limits.minTv, limits.maxTv).reversed().map { SettingOption("shutter_$it", shutterLabel(it), "$it/256") },
    )

    // --- XML ---

    private fun parseXml(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml.trim().substringAfter("?>").let { "<r>$it</r>" })))
            .documentElement

    private fun childElements(node: Node): List<Element> =
        (0 until node.childNodes.length).map { node.childNodes.item(it) }.filterIsInstance<Element>()

    private fun elements(node: Element, tag: String): List<Element> {
        val list = node.getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }
}

/**
 * Position de la molette. L'appareil ne l'indique pas directement : on la reconnaît aux réglages
 * qu'il rend disponibles (relevés sur un FZ1000 ; C1 et C2 apparaissent sous leur mode d'origine).
 */
enum class ShootingMode(val short: String, val label: String) {
    INTELLIGENT_AUTO("iA", "Auto intelligent"),
    PROGRAM("P", "Programme"),
    APERTURE("A", "Priorité ouverture"),
    SHUTTER("S", "Priorité vitesse"),
    MANUAL("M", "Manuel"),
    SCENE("SCN", "Scène"),
    CREATIVE("Créatif", "Commande créative"),
    VIDEO("Vidéo", "Vidéo créative");

    val apertureEditable get() = this == APERTURE || this == MANUAL
    val shutterEditable get() = this == SHUTTER || this == MANUAL

    companion object {
        fun detect(enabled: Set<String>): ShootingMode? = when {
            enabled.isEmpty() -> null
            "menu_item_id_f_and_ss" in enabled -> MANUAL
            "menu_item_id_shutter_speed" in enabled -> SHUTTER
            "menu_item_id_aperture" in enabled -> APERTURE
            "menu_item_id_program_shift" in enabled -> PROGRAM
            "menu_item_id_scn_gid" in enabled -> SCENE
            "menu_item_id_crtv_ctrl" in enabled -> CREATIVE
            // Seule position sans réglages de format photo
            "menu_item_id_asprat" !in enabled -> VIDEO
            else -> INTELLIGENT_AUTO
        }
    }
}

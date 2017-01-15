package obj

import f
import i

/**
 * Created by elect on 27/11/2016.
 */

/**
 *  @class  ObjFileMtlImporter
 *  @brief  Loads the material description from a mtl file.
 */

class ObjFileMtlImporter(buffer: List<String>, private val m_pModel: Model) {

    init {
        if (m_pModel.m_pDefaultMaterial == null)
            m_pModel.m_pDefaultMaterial = Material("default")
        load(buffer)
    }

    fun load(buffer: List<String>) {

        for (line in buffer) {

            val words = line.trim().split("\\s+".toRegex())

            when (words[0][0]) {
                'k', 'K' -> when (words[0][1]) {
                // Ambient color
                    'a' -> m_pModel.m_pCurrentMaterial!!.ambient.Set(words[1].f, words[2].f, words[3].f)
                // Diffuse color
                    'd' -> m_pModel.m_pCurrentMaterial!!.diffuse.Set(words[1].f, words[2].f, words[3].f)
                    's' -> m_pModel.m_pCurrentMaterial!!.specular.Set(words[1].f, words[2].f, words[3].f)
                    'e' -> m_pModel.m_pCurrentMaterial!!.emissive.Set(words[1].f, words[2].f, words[3].f)
                }
                'd' ->
                    if (words[0] == "disp") // A displacement map
                        getTexture(line)
                    else
                        m_pModel.m_pCurrentMaterial!!.alpha = words[1].f  // Alpha value
                'n', 'N' ->
                    when (words[0][1]) {
                    // Specular exponent
                        's' -> m_pModel.m_pCurrentMaterial!!.shineness = words[1].f
                    // Index Of refraction
                        'i' -> m_pModel.m_pCurrentMaterial!!.ior = words[1].f
                    // New material
                        'e' -> createMaterial(words[1])
                    }
                'm', 'b', 'r' -> getTexture(line)
                'i' -> m_pModel.m_pCurrentMaterial!!.illumination_model = words[1].i
            }
        }
    }

    // -------------------------------------------------------------------
    //  Gets a texture name from data.
    fun getTexture(line: String) {

        val words = line.substringBefore('#').split("\\s+".toRegex())   // get rid of comment
        var type: Material.Texture.Type? = null
        var clamped = false

        if (words[0] == "refl" && TypeOption in words)
            type = reflMap[words[words.indexOf(TypeOption) + 1]]
        else
            type = tokenMap[words[0]]

        if (type == null)
            throw Error("OBJ/MTL: Encountered unknown texture type")

        if (ClampOption in words)
            clamped = words[words.indexOf(ClampOption) + 1] == "on"

        m_pModel.m_pCurrentMaterial!!.textures.add(Material.Texture(words.last(), type, clamped))
    }

    // -------------------------------------------------------------------
    //  Creates a material from loaded data.
    fun createMaterial(matName: String) {

        val mat = m_pModel.m_MaterialMap[matName]

        if (mat == null) {
            // New Material created
            m_pModel.m_pCurrentMaterial = Material(matName)
            m_pModel.m_MaterialLib.add(matName)
            m_pModel.m_MaterialMap.put(matName, m_pModel.m_pCurrentMaterial!!)
        }
        // Use older material
        else m_pModel.m_pCurrentMaterial = mat
    }
}

// Material specific token map
val tokenMap = mapOf(
        "map_Kd" to Material.Texture.Type.diffuse,
        "map_Ka" to Material.Texture.Type.ambient,
        "map_Ks" to Material.Texture.Type.specular,
        "map_d" to Material.Texture.Type.opacity,
        "map_emissive" to Material.Texture.Type.emissive, "map_Ke" to Material.Texture.Type.emissive,
        "map_bump" to Material.Texture.Type.bump, "map_Bump" to Material.Texture.Type.bump, "bump" to Material.Texture.Type.bump,
        "map_Kn" to Material.Texture.Type.normal,
        "disp" to Material.Texture.Type.disp,
        "map_ns" to Material.Texture.Type.specularity)

val reflMap = mapOf(
        "sphere" to Material.Texture.Type.reflectionSphere,
        "cube_top" to Material.Texture.Type.reflectionCubeTop,
        "cube_bottom" to Material.Texture.Type.reflectionCubeBottom,
        "cube_front" to Material.Texture.Type.reflectionCubeFront,
        "cube_back" to Material.Texture.Type.reflectionCubeBack,
        "cube_left" to Material.Texture.Type.reflectionCubeLeft,
        "cube_right" to Material.Texture.Type.reflectionCubeRight)

// texture option specific token
const val ClampOption = "-clamp"
const val TypeOption = "-Type"
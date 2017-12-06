package assimp

import assimp.format.X.XFileImporter
import assimp.format.assbin.AssbinLoader
import assimp.format.collada.ColladaLoader
import assimp.format.md2.MD2Importer
import assimp.format.md3.MD3Importer
import assimp.format.md5.MD5Importer
import assimp.format.obj.ObjFileImporter
import assimp.format.ply.PlyLoader
import assimp.format.stl.STLImporter

/**
 * Created by elect on 13/11/2016.
 */

var NO_VALIDATEDS_PROCESS = false


val AI_CONFIG_IMPORT_MD2_KEYFRAME = "IMPORT_MD2_KEYFRAME"

val importerInstanceList
    get() = ArrayList<BaseImporter>().apply {
        with(ASSIMP.BUILD.NO) {
            // ----------------------------------------------------------------------------
            // Add an instance of each worker class here
            // (register_new_importers_here)
            // ---------------------------------------------------------------------------- {
            //if (!assimp.ASSIMP_BUILD_NO_MD2_IMPORTER) add(Md2Importer())
            if (!STL_IMPORTER) add(STLImporter())
            if (!OBJ_IMPORTER) add(ObjFileImporter())
            if (!PLY_IMPORTER) add(PlyLoader())
            if (!COLLADA_IMPORTER) add(ColladaLoader())
            if (!ASSBIN_IMPORTER) add(AssbinLoader())
            if (!MD2_IMPORTER) add(MD2Importer())
            if (!MD3_IMPORTER) add(MD3Importer())
            if (!MD5_IMPORTER) add(MD5Importer())
            if (!X_IMPORTER) add(XFileImporter())
        }
    }

fun getPostProcessingStepInstanceList(): List<BaseProcess> = listOf();
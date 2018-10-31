package assimp.format.blender

import assimp.*
import assimp.format.X.*
import glm_.*
import uno.kotlin.parseInt
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.io.FileOutputStream
import java.util.*
import java.util.zip.GZIPInputStream
import kotlin.collections.ArrayList
import kotlin.math.*


private lateinit var buffer: ByteBuffer

private val tokens = "BLENDER"

// typedef std::map<uint32_t, const MLoopUV *> TextureUVMapping;
// key is material number, value is the TextureUVMapping for the material
// typedef std::map<uint32_t, TextureUVMapping> MaterialTextureUVMappings;
private typealias TextureUVMap = MutableMap<Int, MLoopUV>
private typealias MaterialTextureUVMap = MutableMap<Int, TextureUVMap>

class BlenderImporter : BaseImporter() {    // TODO should this be open? The C++ version has protected methods
	// TODO check member visibility

	private val modifierCache = BlenderModifierShowcase()

    /** Returns whether the class can handle the format of the given file.  */
    override fun canRead(file: String, ioSystem: IOSystem, checkSig: Boolean): Boolean {

        val extension = getExtension(file)
        if (extension == "blend") return true
        else if (extension.isEmpty() || checkSig) {
            // TODO ("check is blend file")
            // note: this won't catch compressed files
//            return SearchFileHeaderForToken(pIOHandler,pFile, TokensForSearch,1);
        }
        return false
    }

    override val info
        get() = AiImporterDesc(
                name = "Blender 3D Importer \nhttp://www.blender3d.org",
                comments = "No animation support yet",
                flags = AiImporterFlags.SupportBinaryFlavour.i,
                minMajor = 0,
                minMinor = 0,
                maxMajor = 2,
                maxMinor = 50,
                fileExtensions = listOf("blend"))

    override fun internReadFile(file: String, ioSystem: IOSystem, scene: AiScene) {

        val stream = ioSystem.open(file)

        buffer = stream.readBytes()

        var match = buffer.strncmp(tokens)
        if (!match) {
            // Check for presence of the gzip header. If yes, assume it is a
            // compressed blend file and try uncompressing it, else fail. This is to
            // avoid uncompressing random files which our loader might end up with.

            val output = File("temp")   // TODO use a temp outputStream instead of writing to disc, maybe?
	        // we could use ByteArrayInputStream / ByteArrayOutputStream
	        // the question is what this would do to memory requirements for big files
	        // we would basically keep up to 3 copies of the file in memory (buffer, output, input)
            output.deleteOnExit()

            GZIPInputStream(stream.read()).use { gzip ->

                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(1024)
                    var len = gzip.read(buffer)
                    while (len != -1) {
                        out.write(buffer, 0, len)
                        len = gzip.read(buffer)
                    }
                }
            }
            val fc = RandomAccessFile(output, "r").channel
            buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size()).order(ByteOrder.nativeOrder())
            // .. and retry
            match = buffer.strncmp(tokens)
            if (!match) throw Error("Found no BLENDER magic word in decompressed GZIP file")
        }
	    buffer.pos += tokens.length

        val db = FileDatabase().apply {
            i64bit = buffer.get().c == '-'      // 32 bit should be '_'
            little = buffer.get().c == 'v'      // big endian should be 'V'
        }
        val major = buffer.get().c.parseInt()
        val minor = buffer.get().c.parseInt() * 10 + buffer.get().c.parseInt()
        logger.info("Blender version is $major.$minor (64bit: ${db.i64bit}, little endian: ${db.little})")
	    if(ASSIMP.BLENDER_DEBUG) logger.info { "Blender DEBUG ENABLED" }

        db.reader = buffer.slice().order(if(db.little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        parseBlendFile(db)

        val blendScene = extractScene(db)

	    blendScene.convertBlendFile(db)
    }

    private fun parseBlendFile(out: FileDatabase) {

        val dnaReader = DnaParser(out)
        var dna: DNA? = null

        out.entries.ensureCapacity(128)
        // even small BLEND files tend to consist of many file blocks
        val parser = SectionParser(out.reader, out.i64bit)

        // first parse the file in search for the DNA and insert all other sections into the database
        while (true) {
            parser.next()
            val head = parser.current.copy()

            if (head.id == "ENDB")
                break // only valid end of the file
            else if (head.id == "DNA1") {
                dnaReader.parse()
                dna = dnaReader.dna
                continue
            }

            out.entries += head
        }
        if (dna == null) throw Error("SDNA not found")

        out.entries.sort()
    }

    private fun extractScene(file: FileDatabase): Scene {

        val sceneIndex = file.dna.indices["Scene"]?.toInt() ?: throw Error("There is no `Scene` structure record")

        val ss = file.dna.structures[sceneIndex]

	    // we need a scene somewhere to start with.
	    val block = file.entries.find {
		    // Fix: using the DNA index is more reliable to locate scenes
		    //if (bl.id == "SC") {
		    it.dnaIndex == sceneIndex
	    } ?: throw Error("There is not a single `Scene` record to load")

	    file.reader.pos = block.start

	    val out = Scene()
        ss.convert(out)

	    if(!ASSIMP.BLENDER_NO_STATS) {
		    val stats = file.stats
		    logger.info {
			    "(Stats) Fields read: ${stats.fieldsRead}, " +
			    "pointers resolved: ${stats.pointersResolved}, " +
			    "cache hits: ${stats.cacheHits}, " +
			    "cached objects: ${stats.cachedObjects}"
		    }
	    }

	    return out
    }

	protected fun Scene.convertBlendFile(db: FileDatabase): AiScene {

		val conv = ConversionData(db)

		// FIXME it must be possible to take the hierarchy directly from
		// the file. This is terrible. Here, we're first looking for
		// all objects which don't have parent objects at all -
		val noParents = LinkedList<Object>()
		(base.first as? Base)?.forEach{

			it.obj?.let { obj ->
				if (obj.parent == null) {
					noParents.pushBack(obj)
				} else {
					conv.objects.add(obj)
				}
			}
		}
		basact?.forEach {
			it.obj?.let { obj ->
				if(obj.parent != null){
					conv.objects.add(obj)
				}
			}
		}

		if(noParents.isEmpty()){
			throw Error("Expected at least one object with no parent")
		}

		val out = AiScene()
		out.rootNode = AiNode("<BlenderRoot>")
		val root = out.rootNode

		root.numChildren = noParents.size
		root.children = MutableList(root.numChildren) {
			val node: AiNode = convertNode(noParents[it], conv)
			node.parent = root
			node
		}

		buildMaterials(conv)

		if(conv.meshes.size > 0) {
			out.numMeshes = conv.meshes.size
			out.meshes = MutableList(conv.meshes.size) {
				conv.meshes[it]
			}
			conv.meshes.clear()
		}

		if(conv.lights.size > 0) {
			out.numLights = conv.lights.size
			out.lights = MutableList(conv.lights.size) {
				conv.lights[it]
			}
			conv.lights.clear()
		}

		if(conv.cameras.size > 0) {
			out.numCameras = conv.cameras.size
			out.cameras = MutableList(conv.cameras.size) {
				conv.cameras[it]
			}
			conv.cameras.clear()
		}

		if(conv.materials.size > 0) {
			out.numMaterials = conv.materials.size
			out.materials = MutableList(conv.materials.size) {
				conv.materials[it]
			}
			conv.materials.clear()
		}

		if(conv.textures.size > 0) {
			out.numTextures = conv.textures.size
			for((name, tex) in conv.textures) {
				tex.achFormatHint
				// TODO convert to gli.Texture out.textures[name] = tex ??
			}
//			out->mTextures = new aiTexture*[out->mNumTextures = static_cast<unsigned int>( conv.textures->size() )];
//			std::copy(conv.textures->begin(),conv.textures->end(),out->mTextures);
//			conv.textures.dismiss();
		}


        // acknowledge that the scene might come out incomplete
  		// by Assimp's definition of `complete`: blender scenes
  		// can consist of thousands of cameras or lights with
  		// not a single mesh between them.
		if(out.numMeshes == 0){
			out.flags = out.flags or AI_SCENE_FLAGS_INCOMPLETE
		}

		return out
	}

	private fun Scene.convertNode(obj: Object, conv: ConversionData, parentTransform: AiMatrix4x4 = AiMatrix4x4()): AiNode {

		fun notSupportedObjectType(obj: Object, type: String) {
			logger.warn { "Object `${obj.id.name}` - type is unsupported: `$type`, skipping" }
		}

		val children = LinkedList<Object>()
		for(it in conv.objects) {
			if(it.parent == obj) {
				children.pushBack(it)
				conv.objects.remove(it)
			}
		}

		val node = AiNode(obj.id.name.substring(2)) // skip over the name prefix 'OB'

		obj.data?.let { data ->
			when(obj.type) {

				Object.Type.EMPTY   -> {} // do nothing
				Object.Type.MESH    -> {
					val old = conv.meshes.size

					checkActualType(data, "Mesh")
					convertMesh(obj, data as Mesh, conv, conv.meshes)

					if(conv.meshes.size > old) {
						node.meshes = IntArray(conv.meshes.size - old) { it + old }
					}
				}
				Object.Type.LAMP    -> {
					checkActualType(data, "Lamp")
					val light = convertLight(obj, data as Lamp)
					conv.lights.pushBack(light)
				}
				Object.Type.CAMERA  -> {
					checkActualType(data, "Camera")
					val camera = convertCamera(obj, data as Camera)
					conv.cameras.pushBack(camera)
				}
				Object.Type.CURVE   -> notSupportedObjectType(obj, "Curve")
				Object.Type.SURF    -> notSupportedObjectType(obj, "Surf")
				Object.Type.FONT    -> notSupportedObjectType(obj, "Font")
				Object.Type.MBALL   -> notSupportedObjectType(obj, "Mball")
				Object.Type.WAVE    -> notSupportedObjectType(obj, "Wave")
				Object.Type.LATTICE -> notSupportedObjectType(obj, "Lattice")
				else -> throw Error("When should be exhaustive")
			}
			Unit // return Unit from let explicitly so that when and the contained if statements don't need to be exhaustive
		}

		for(x in 0 until 4) {
			for(y in 0 until 4) {
				node.transformation[y][x] = obj.obmat[x][y]     // TODO do I need to change anything here
				// C++ Assimp uses row-based and kotlin assimp is column-based matrices.
				// https://github.com/kotlin-graphics/assimp/wiki/Instructions-for-porting-code-&-Differences-between-the-C---and-Kotlin-version#matrices
			}
		}

		val m = parentTransform.inverse()
		node.transformation = m*node.transformation

		if(children.size > 0) {
			node.numChildren = children.size
			node.children = MutableList(node.numChildren) {
				convertNode(children[it], conv, node.transformation * parentTransform)
						.apply { parent = node }
			}
		}

		// apply modifiers
		modifierCache.applyModifiers(node, conv, this, obj)

		return node
	}

	private fun checkActualType(dt: ElemBase, check: String): Unit {
		assert(dt.dnaType == check) {
			"Expected object `$dt` to be of type `$check`, but it claims to be a `${dt.dnaType}` instead"
		}
	}

	private fun buildMaterials(conv: ConversionData) {
		TODO("buildMaterials")
	}

	private fun Scene.convertMesh(obj: Object, mesh: Mesh, conv: ConversionData, meshList/* temp in C version */: ArrayList<AiMesh>) {

		/*
		// TODO: Resolve various problems with BMesh triangulation before re-enabling.
        //       See issues #400, #373, #318  #315 and #132.
	#if defined(TODO_FIX_BMESH_CONVERSION)
	    BlenderBMeshConverter BMeshConverter( mesh );
	    if ( BMeshConverter.ContainsBMesh( ) )
	    {
	        mesh = BMeshConverter.TriangulateBMesh( );
	    }
	#endif
		 */

		// typedef std::pair<const int,size_t> MyPair;
		if((mesh.totface == 0 && mesh.totloop == 0) || mesh.totvert == 0 ) {
			return
		}

		// extract nullables
		val faces = requireNotNull(mesh.mface) { "mface in mesh is null!" }
		val verts = requireNotNull(mesh.mvert) { "mvert in mesh is null!" }
		val loops = requireNotNull(mesh.mloop) { "mloop in mesh is null!" }
		val polys = requireNotNull(mesh.mpoly) { "mpoly in mesh is null!" }
		val mats = requireNotNull(mesh.mat) { "mat in mesh is null!" }

		// some sanity checks
		require(mesh.totface <= faces.size ) { "Number of faces is larger than the corresponding array" }

		require(mesh.totvert <= verts.size ) { "Number of vertices is larger than the corresponding array" }

		require(mesh.totloop <= loops.size ) { "Number of loops is larger than the corresponding array" }

		// collect per-submesh numbers
		val perMat = mutableMapOf<Int, Int>()
		val perMatVerts = mutableMapOf<Int, Int>()

		for(i in 0 until mesh.totface) {
			val face = faces[i]
			perMat[face.matNr] = perMat.getOrDefault(face.matNr, 0) + 1
			val vertCount = if(face.v4 != 0) 4 else 3
			perMatVerts[face.matNr] = perMatVerts.getOrDefault(face.matNr, 0) + vertCount
		}
		for(i in 0 until mesh.totpoly) {
			val poly = polys[i]

			perMat[poly.matNr.i] = perMat.getOrDefault(poly.matNr, 0) + 1
			perMatVerts[poly.matNr.i] = perMat.getOrDefault(poly.matNr, 0) + poly.totLoop
		}

		// ... and allocate the corresponding meshes
		val old = meshList.size
		meshList.ensureCapacity(meshList.size + perMat.size)

		val matNumToMeshIndex = mutableMapOf<Int, Int>()
		fun getMesh(matNr: Int): AiMesh = meshList[matNumToMeshIndex[matNr]!!]

		for((matNr, faceCount) in perMat) {

			matNumToMeshIndex[matNr] = meshList.size

			val out = AiMesh()
			meshList.pushBack(out)

			out.vertices = MutableList(perMatVerts[matNr]!!) { AiVector3D() }
			out.normals = MutableList(perMatVerts[matNr]!!) { AiVector3D() }

			//out->mNumFaces = 0
			//out->mNumVertices = 0
			out.faces = MutableList(faceCount) { mutableListOf<Int>() }

			// all sub-meshes created from this mesh are named equally. this allows
			// curious users to recover the original adjacency.
			out.name = mesh.id.name.substring(2)
			// skip over the name prefix 'ME'

			// resolve the material reference and add this material to the set of
			// output materials. The (temporary) material index is the index
			// of the material entry within the list of resolved materials.
			if (mesh.mat != null) {

				val materials = mesh.mat!!

				if(matNr >= materials.size) {
					throw IndexOutOfBoundsException("Material index is out of range")
				}

				val mat = checkNotNull(materials[matNr])

				val index = conv.materialsRaw.indexOf(mat)
				if (index == -1) {
					out.materialIndex = conv.materialsRaw.size
					conv.materialsRaw.pushBack(mat)
				} else {
					out.materialIndex = index
				}
			} else {
				out.materialIndex = -1 // static_cast<unsigned int>( -1 );
			}

		}

		fun AiMesh.convertVertex(pos: Int, vertIndex: Int, f: AiFace) {
			if(pos >= mesh.totvert) {
				throw IndexOutOfBoundsException("Vertex index out of range")
			}
			val v = verts[pos]

			vertices[numVertices] = AiVector3D(v.co)
			normals[numVertices] = AiVector3D(v.no)
			f[vertIndex] = numVertices
			numVertices++
		}

		for(i in 0 until mesh.totface) {

			val mf = faces[i]

			val out = getMesh(mf.matNr)

			val f = out.faces[out.numFaces] // AiFace == MutableList
			out.numFaces++

			out.convertVertex(mf.v1, 0, f)
			out.convertVertex(mf.v2, 1, f)
			out.convertVertex(mf.v3, 2, f)
			if(mf.v4 > 0) {
				out.convertVertex(mf.v4, 3, f)
				out.primitiveTypes = out.primitiveTypes or AiPrimitiveType.POLYGON
			} else {
				out.primitiveTypes = out.primitiveTypes or AiPrimitiveType.TRIANGLE
			}
		}

		for(i in 0 until mesh.totpoly) {

			val mp = polys[i]

			val out = getMesh(mp.matNr)

			val f = out.faces[out.numFaces]
			out.numFaces++


			for(j in 0 until mp.totLoop) {
				val loop = loops[mp.loopStart + j]

				out.convertVertex(loop.v, j, f)
			}
			if(mp.totLoop == 3) {
				out.primitiveTypes = out.primitiveTypes or AiPrimitiveType.TRIANGLE
			} else {
				out.primitiveTypes = out.primitiveTypes or AiPrimitiveType.POLYGON
			}
		}
	    // TODO should we create the TextureUVMapping map in Convert<Material> to prevent redundant processing?

	    // create texture <-> uvname mapping for all materials
	    // key is texture number, value is data *
	    // typedef std::map<uint32_t, const MLoopUV *> TextureUVMapping;
	    // key is material number, value is the TextureUVMapping for the material
	    // typedef std::map<uint32_t, TextureUVMapping> MaterialTextureUVMappings;

		val matTexUvMappings: MaterialTextureUVMap = mutableMapOf()

		val maxMat = mats.size
		for (m in 0 until maxMat) {
			val mat = checkNotNull(mats[m])

			val texUV: TextureUVMap = mutableMapOf()
			val maxTex = mat.mTex.size
			for(t in 0 until maxTex) {
				val tex = mat.mTex[t]
				if(tex != null && tex.uvName.isNotEmpty()) {
					// get the CustomData layer for given uvname and correct type
					val loop = TODO("getCustomDataLayerData(mesh.ldata, CustomDataType.MLoopUv, tex.uvName)")
					if(loop != null) {
						texUV[t] = loop
					}
				}
			}
			if(texUV.isNotEmpty()) {
				matTexUvMappings[m] = texUV
			}
		}

		// collect texture coordinates, they're stored in a separate per-face buffer
		if(mesh.mtface != null || mesh.mloopuv != null) {       // FIXME does this need to be &&, the C code disagrees, right now we fail in the same situation

			if(mesh.totface > 0 && mesh.totface > mesh.mtface!!.size) {
				throw IndexOutOfBoundsException("number of uv faces is larger than the corresponding uv face array (#1)")
			}

			for(itMesh in meshList.subList(old, meshList.size)) {

				assert(itMesh.numVertices > 0 && itMesh.numFaces > 0)

				val itMatTexUvMap = matTexUvMappings[itMesh.materialIndex]
				if(itMatTexUvMap == null) {
					// default behaviour like before
					itMesh.textureCoords[0] = MutableList(itMesh.numVertices) { FloatArray(2) }
				} else {
					// create texture coords for every mapped tex
					for (i in 0 until itMatTexUvMap.size) {
						itMesh.textureCoords[i] = MutableList(itMesh.numVertices)  { FloatArray(2) }
					}
				}
				itMesh.numFaces = 0
				itMesh.numVertices = 0
			}

			for(meshIndex in 0 until mesh.totface) {

				val mtface = mesh.mtface!![meshIndex]

				val out = getMesh(faces[meshIndex].matNr)
				val f = out.faces[out.numFaces]
				out.numFaces++

				for(i in 0 until f.size) {
					val vo = out.textureCoords[0][out.numVertices]
					vo[0] = mtface.uv[i][0] // x
					vo[1] = mtface.uv[i][1] // y
					out.numVertices++
				}
			}

			for(loopIndex in 0 until mesh.totpoly) {
				val poly = polys[loopIndex]
				val out = getMesh(poly.matNr)

				val f = out.faces[out.numFaces]
				out.numFaces++

				val itMatTexUvMap = matTexUvMappings[poly.matNr]
				if(itMatTexUvMap == null) {
					// old behavior
					for(j in 0 until f.size) {
						val vo = out.textureCoords[0][out.numVertices]
						val uv = mesh.mloopuv!![poly.loopStart + j]

						vo[0] = uv.uv[0]
						vo[1] = uv.uv[1]
						out.numVertices++
					}
				} else {
					// create textureCoords for every mapped tex
					for(m in 0 until itMatTexUvMap.size) {
						val tm = itMatTexUvMap[m]!!
						// TODO I think there is a bug here!!!!!!!!!
						for(j in 0 until f.size) {
							val vo = out.textureCoords[m][out.numVertices]
							val uv = tm.uv
							vo[0] = uv[0]
							vo[1] = uv[1]
							out.numVertices++

						/*
					// create textureCoords for every mapped tex
	                for (uint32_t m = 0; m < itMatTexUvMapping->second.size(); ++m) {
	                    const MLoopUV *tm = itMatTexUvMapping->second[m];
	                    aiVector3D* vo = &out->mTextureCoords[m][out->mNumVertices];
	                    uint32_t j = 0;
	                    for (; j < f.mNumIndices; ++j, ++vo) {
	                        const MLoopUV& uv = tm[v.loopstart + j];
	                        vo->x = uv.uv[0];
	                        vo->y = uv.uv[1];
	                    }
	                    // only update written mNumVertices in last loop
	                    // TODO why must the numVertices be incremented here?
	                    if (m == itMatTexUvMapping->second.size() - 1) {
	                        out->mNumVertices += j;
	                    }
	                }
						 */
						}
					}
				}
			}
		}

		if(mesh.tface != null) {
			val tfaces = mesh.tface!!

			assert(mesh.totface <= tfaces.size) { "Number of faces is larger than the corresponding UV face array (#2)" }

			for(itMesh in meshList) {
				assert(itMesh.numVertices > 0 && itMesh.numFaces > 0)

				// TODO
			}
		}
		/*

	    // collect texture coordinates, old-style (marked as deprecated in current blender sources)
	    if (mesh->tface) {
	        if (mesh->totface > static_cast<int> ( mesh->tface.size())) {
	            ThrowException("Number of faces is larger than the corresponding UV face array (#2)");
	        }
	        for (std::vector<aiMesh*>::iterator it = temp->begin()+old; it != temp->end(); ++it) {
	            ai_assert((*it)->mNumVertices && (*it)->mNumFaces);

	            (*it)->mTextureCoords[0] = new aiVector3D[(*it)->mNumVertices];
	            (*it)->mNumFaces = (*it)->mNumVertices = 0;
	        }

	        for (int i = 0; i < mesh->totface; ++i) {
	            const TFace* v = &mesh->tface[i];

	            aiMesh* const out = temp[ mat_num_to_mesh_idx[ mesh->mface[i].mat_nr ] ];
	            const aiFace& f = out->mFaces[out->mNumFaces++];

	            aiVector3D* vo = &out->mTextureCoords[0][out->mNumVertices];
	            for (unsigned int i = 0; i < f.mNumIndices; ++i,++vo,++out->mNumVertices) {
	                vo->x = v->uv[i][0];
	                vo->y = v->uv[i][1];
	            }
	        }
	    }

	    // collect vertex colors, stored separately as well
	    if (mesh->mcol || mesh->mloopcol) {
	        if (mesh->totface > static_cast<int> ( (mesh->mcol.size()/4)) ) {
	            ThrowException("Number of faces is larger than the corresponding color face array");
	        }
	        for (std::vector<aiMesh*>::iterator it = temp->begin()+old; it != temp->end(); ++it) {
	            ai_assert((*it)->mNumVertices && (*it)->mNumFaces);

	            (*it)->mColors[0] = new aiColor4D[(*it)->mNumVertices];
	            (*it)->mNumFaces = (*it)->mNumVertices = 0;
	        }

	        for (int i = 0; i < mesh->totface; ++i) {

	            aiMesh* const out = temp[ mat_num_to_mesh_idx[ mesh->mface[i].mat_nr ] ];
	            const aiFace& f = out->mFaces[out->mNumFaces++];

	            aiColor4D* vo = &out->mColors[0][out->mNumVertices];
	            for (unsigned int n = 0; n < f.mNumIndices; ++n, ++vo,++out->mNumVertices) {
	                const MCol* col = &mesh->mcol[(i<<2)+n];

	                vo->r = col->r;
	                vo->g = col->g;
	                vo->b = col->b;
	                vo->a = col->a;
	            }
	            for (unsigned int n = f.mNumIndices; n < 4; ++n);
	        }

	        for (int i = 0; i < mesh->totpoly; ++i) {
	            const MPoly& v = mesh->mpoly[i];
	            aiMesh* const out = temp[ mat_num_to_mesh_idx[ v.mat_nr ] ];
	            const aiFace& f = out->mFaces[out->mNumFaces++];

	            aiColor4D* vo = &out->mColors[0][out->mNumVertices];
				const ai_real scaleZeroToOne = 1.f/255.f;
	            for (unsigned int j = 0; j < f.mNumIndices; ++j,++vo,++out->mNumVertices) {
	                const MLoopCol& col = mesh->mloopcol[v.loopstart + j];
	                vo->r = ai_real(col.r) * scaleZeroToOne;
	                vo->g = ai_real(col.g) * scaleZeroToOne;
	                vo->b = ai_real(col.b) * scaleZeroToOne;
	                vo->a = ai_real(col.a) * scaleZeroToOne;
	            }

	        }

	    }
		*/
	}

	private fun convertCamera(obj: Object, cam: Camera): AiCamera {

		val out = AiCamera()
		out.name = obj.id.name.substring(2)

		out.position = AiVector3D(0f)
		out.up = AiVector3D(0f, 1f,0f)
		out.lookAt = AiVector3D(0f, 0f, -1f)

		if(cam.sensorX > 0f && cam.lens > 0f) {
			out.horizontalFOV = 2f * atan2(cam.sensorX, 2f * cam.lens)
		}

		out.clipPlaneNear = cam.clipSta
		out.clipPlaneFar = cam.clipEnd

		return out
	}

	private fun convertLight(obj: Object, lamp: Lamp): AiLight {

		val out = AiLight()
		out.name = obj.id.name.substring(2)

		when(lamp.type) {
			Lamp.Type.Local -> {
				out.type = AiLightSourceType.POINT
			}
			Lamp.Type.Sun   -> {
				out.type = AiLightSourceType.DIRECTIONAL

				// blender orients directional lights as facing toward -z
				out.direction = AiVector3D(0f, 0f, -1f)
				out.up = AiVector3D(0f, 1f, 0f)
			}
			Lamp.Type.Area  -> {
				out.type = AiLightSourceType.AREA

				if(lamp.areaShape == 0.s){
					out.size = AiVector2D(lamp.areaSize, lamp.areaSize)
				} else {
					out.size = AiVector2D(lamp.areaSize, lamp.areaSizeY)
				}

				// blender orients directional lights as facing toward -z
				out.direction = AiVector3D(0f, 0f, -1f)
				out.up = AiVector3D(0f, 1f, 0f)
			}
			else -> {} // TODO missing light types??? do nothing?? realy??
		}

		out.colorAmbient = AiColor3D(lamp.r, lamp.b, lamp.g) * lamp.energy
		out.colorSpecular= AiColor3D(lamp.r, lamp.b, lamp.g) * lamp.energy
		out.colorDiffuse = AiColor3D(lamp.r, lamp.b, lamp.g) * lamp.energy

		return out
	}
}

fun error(policy: ErrorPolicy, value: Any?, message: String?): Unit = when(policy) {
    ErrorPolicy.Warn -> logger.warn { "value: $value, $message" }
    ErrorPolicy.Fail -> throw Error( "value: $value, $message" )
    ErrorPolicy.Igno -> if (ASSIMP.BLENDER_DEBUG) logger.info { "value: $value, $message" } else Unit
}

package assimp.format.blender

import assimp.*
import glm_.*
import kotlin.math.min
import kotlin.reflect.KFunction0
import kotlin.reflect.KMutableProperty0
import assimp.format.blender.ErrorPolicy as Ep

/** Represents a data structure in a BLEND file. A Structure defines n fields and their locations and encodings the input stream. Usually, every
 *  Structure instance pertains to one equally-named data structure in the
 *  BlenderScene.h header. This class defines various utilities to map a
 *  binary `blob` read from the file to such a structure instance with
 *  meaningful contents. */
class Structure {

    // publicly accessible members
    var name = ""
    val fields = ArrayList<Field>()
    val indices = mutableMapOf<String, Long>()

    var size = 0L

    var cacheIdx = -1L

    /** Access a field of the structure by its canonical name. The pointer version returns NULL on failure while
     *  the reference version raises an import error. */
    operator fun get(ss: String): Field {
        val index = indices[ss] ?: throw Exception("BlendDNA: Did not find a field named `$ss` in structure `$name`")
        return fields[index.i]
    }
//    fun get_(ss:String) =

    /** Access a field of the structure by its index */
    operator fun get(i: Long) = fields.getOrElse(i.i) { throw Error("BlendDNA: There is no field with index `$i` in structure `$name`") }

    override fun equals(other: Any?) = other is Structure && name == other.name // name is meant to be an unique identifier

    fun convertInt() = convertDispatcher<Int>()

    /** Try to read an instance of the structure from the stream and attempt to convert to `T`. This is done by an
     *  appropriate specialization. If none is available, a compiler complain is the result.
     *  @param dest Destination value to be written
     *  @param db File database, including input stream. */
    val convertChar
        get() = when (name) {
        // automatic rescaling from char to float and vice versa (seems useful for RGB colors)
            "float" -> (db.reader.float * 255f).c
            "double" -> (db.reader.double * 255f).c
            else -> convertDispatcher()
        }

    val convertShort
        get() = when (name) {
        // automatic rescaling from short to float and vice versa (seems to be used by normals)
            "float" -> {
                var f = db.reader.float
                if (f > 1f) f = 1f
                (f * 32767f).s
            }
            "double" -> (db.reader.double * 32767.0).s
            else -> convertDispatcher()
        }

    //        return when (T::class) {
//            Int::class -> convertDispatcher(db)
//
    val convertFloat
        get() = when (name) {
        // automatic rescaling from char to float and vice versa (seems useful for RGB colors)
            "char" -> db.reader.get() / 255f
        // automatic rescaling from short to float and vice versa (used by normals)
            "short" -> db.reader.short / 32767f
            else -> convertDispatcher()
        }

    fun convertPointer() = if (db.i64bit) db.reader.long else db.reader.int.L

    fun <T> convertDispatcher(): T = when (name) {
        "int" -> db.reader.int as T
        "short" -> db.reader.short as T
        "char" -> db.reader.get().c as T
        "float" -> db.reader.float as T
        "double" -> db.reader.double as T
        else -> throw Error("Unknown source for conversion to primitive data type: $name")
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + fields.hashCode()
        result = 31 * result + indices.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + cacheIdx.hashCode()
        return result
    }

    /** field parsing for 1d arrays */
    fun readFieldString(errorPolicy: Ep, name: String): String {

        var dest = ""
        val old = db.reader.pos
        try {
            val f = get(name)
            val s = db.dna[f.type]

            // is the input actually an array?
            if (f.flags hasnt FieldFlag.Array)
                throw Error("Field `$name` of structure `${this.name}` ought to be a string")

            db.reader.pos += f.offset.i

            val builder = StringBuilder()

            // size conversions are always allowed, regardless of error_policy
            for (i in 0 until f.arraySizes[0]) {
                val c = s.convertChar
                if (c != NUL) builder += c
                else break
            }

//            for(; i < M; ++i) {
//                _defaultInitializer<ErrorPolicy_Igno>()(out[i])
//            }
            dest = builder.toString()

        } catch (e: Exception) {
            error(errorPolicy, dest, e.message)
        }

        // and recover the previous stream position
        db.reader.pos = old

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.fieldsRead

        return dest
    }

    /** field parsing for 1d arrays */
    fun readFieldFloatArray(errorPolicy: Ep, out: FloatArray, name: String) {

        val old = db.reader.pos
        try {
            val f = get(name)
            val s = db.dna[f.type]

            // is the input actually an array?
            if (f.flags hasnt FieldFlag.Array)
                throw Error("Field `$name` of structure `${this.name}` ought to be an array of size ${out.size}")

            db.reader.pos += f.offset.i

            // size conversions are always allowed, regardless of error_policy
            for (i in 0 until min(f.arraySizes[0].i, out.size))
                out[i] = s.convertFloat

//            for (; i < M; ++i) {
//                _defaultInitializer<ErrorPolicy_Igno>()(out[i])
//            }
        } catch (e: Exception) {
            error(errorPolicy, out, e.message)
        }

        // and recover the previous stream position
        db.reader.pos = old

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.fieldsRead
    }

    /** field parsing for 1d arrays */
    fun readFieldIntArray(errorPolicy: Ep, out: IntArray, name: String) {

        val old = db.reader.pos
        try {
            val f = get(name)
            val s = db.dna[f.type]

            // is the input actually an array?
            if (f.flags hasnt FieldFlag.Array)
                throw Error("Field `$name` of structure `${this.name}` ought to be an array of size ${out.size}")

            db.reader.pos += f.offset.i

            // size conversions are always allowed, regardless of error_policy
            for (i in 0 until min(f.arraySizes[0].i, out.size))
                out[i] = s.convertInt()

//            for (; i < M; ++i) {
//                _defaultInitializer<ErrorPolicy_Igno>()(out[i])
//            }
        } catch (e: Exception) {
            error(errorPolicy, out, e.message)
        }

        // and recover the previous stream position
        db.reader.pos = old

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.fieldsRead
    }

    /** field parsing for 2d arrays */
    fun <T> readFieldArray2(errorPolicy: Ep, out: Array<T>, name: String) {

        val old = db.reader.pos
        try {
            val f = get(name)
            val s = db.dna[f.type]

            // is the input actually an array?
            if (f.flags hasnt FieldFlag.Array) throw Error("Field `$name` of structure `${this.name}` ought to be an array of size ${out.size}*N")

            db.reader.pos += f.offset.i

            // size conversions are always allowed, regardless of error_policy
            for (i in 0 until min(f.arraySizes[0].i, out.size)) {

                val n = out[i]
                if (n is FloatArray) for (j in n.indices) n[j] = s.convertFloat
            }
        } catch (e: Exception) {
            error(errorPolicy, out, e.message)
        }

        // and recover the previous stream position
        db.reader.pos = old

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.fieldsRead
    }

    /** field parsing for pointer or dynamic array types (std::shared_ptr)
     *  The return value indicates whether the data was already cached. */
    fun <T> readFieldPtr(errorPolicy: Ep, out: KMutableProperty0<T?>, name: String, nonRecursive: Boolean = false): Boolean {

        val old = db.reader.pos
        var ptrval = 0L
        val f: Field
        try {
            f = get(name)

            // sanity check, should never happen if the genblenddna script is right
            if (f.flags hasnt FieldFlag.Pointer) throw Error("Field `$name` of structure `${this.name}` ought to be a pointer")

            db.reader.pos += f.offset.i
            ptrval = convertPointer()
            /*  actually it is meaningless on which Structure the Convert is called because the `Pointer` argument
                triggers a special implementation.             */
        } catch (e: Exception) {
            error(errorPolicy, out, e.message)
            out.set(null)
            return false
        }

        // resolve the pointer and load the corresponding structure
        val res = resolvePtr(out, ptrval, f, nonRecursive)
        // and recover the previous stream position
        if (!nonRecursive) db.reader.pos = old

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.fieldsRead

        return res
    }

    /** field parsing for static arrays of pointer or dynamic array types (std::shared_ptr[])
     *  The return value indicates whether the data was already cached. */
//    fun <T>readFieldPtr(out )[N], const char* name,
//    const FileDatabase& db) const
//
    /** field parsing for `normal` values
     *  The return value indicates whether the data was already cached. */
    fun <T> readField(errorPolicy: Ep, out: T, name: String): T {

        val old = db.reader.pos
        try {
            val f = get(name)
            // find the structure definition pertaining to this field
            val s = db.dna[f.type]

            db.reader.pos += f.offset.i
            when (out) {
                is Id -> s.convert(out)
                is ListBase -> s.convert(out)
                is KMutableProperty0<*> -> when (out()) {
                    is Float -> (out as KMutableProperty0<Float>).set(s.convertFloat)
                    is Short -> (out as KMutableProperty0<Short>).set(s.convertShort)
                    is Int -> (out as KMutableProperty0<Int>).set(s.convertInt())
                    is Char -> (out as KMutableProperty0<Char>).set(s.convertChar)
                    else -> throw Error()
                }
                else -> throw Error()
            }
        } catch (e: Exception) {
            error(errorPolicy, out, e.message)
        }

        // and recover the previous stream position
        db.reader.pos = old

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.fieldsRead

        return out
    }

    fun <T> resolvePtr(out: T?, ptrVal: Long, f: Field, nonRecursive: Boolean = false) = when {
        f.type == "ElemBase" || isElem -> resolvePointer(out as KMutableProperty0<ElemBase?>, ptrVal)
        else -> resolvePointer(out as KMutableProperty0<*>, ptrVal, f, nonRecursive)
//        out is FileOffset -> resolvePointer(out, ptrVal, f, nonRecursive)
//        else -> throw Error()
    }

    fun <T> resolvePointer(out: KMutableProperty0<T?>, ptrVal: Long, f: Field, nonRecursive: Boolean = false): Boolean {

        out.set(null) // ensure null pointers work
        if (ptrVal == 0L) return false

        val s = db.dna[f.type]
        // find the file block the pointer is pointing to
        val block = locateFileBlockForAddress(ptrVal)

        // also determine the target type from the block header and check if it matches the type which we expect.
        val ss = db.dna[block.dnaIndex.L]
        if (ss !== s)
            throw Error("Expected target to be of type `${s.name}` but seemingly it is a `${ss.name}` instead")

        // try to retrieve the object from the cache
        db.cache.get(s, out, ptrVal)
        if (out() != null) return true

        // seek to this location, but save the previous stream pointer.
        val pOld = db.reader.pos
        db.reader.pos = block.start + (ptrVal - block.address).i
        // FIXME: basically, this could cause problems with 64 bit pointers on 32 bit systems.
        // I really ought to improve StreamReader to work with 64 bit indices exclusively.

        // continue conversion after allocating the required storage
        val num = block.size / ss.size.i
        if (num > 1) {
            TODO()
            val o = when (f.type) {
                "Object" -> Array(num) { Object() }
                "Camera" -> Array(num) { Camera() }
                else -> throw Error()
            }

            // cache the object before we convert it to avoid cyclic recursion.
            db.cache.set(s, out, ptrVal)

            // if the non_recursive flag is set, we don't do anything but leave the cursor at the correct position to resolve the object.
            if (!nonRecursive) {
                for (i in 0 until num)
                    when (o[i]) {
//                        is Object -> s.convertObject(::_o as KMutableProperty0<Object?>) as T
                    }

                db.reader.pos = pOld
            }
        } else {

            out.set(when (f.type) {
                "Object" -> Object()
                "Camera" -> Camera()
                "World" -> World()
                "Base" -> Base()
                else -> throw Error()
            } as T)

            // cache the object before we convert it to avoid cyclic recursion.
            db.cache.set(s, out, ptrVal)

            // if the non_recursive flag is set, we don't do anything but leave the cursor at the correct position to resolve the object.
            if (!nonRecursive) {
                when (f.type) {
                    "Object" -> s.convertObject(out as KMutableProperty0<Object?>)
                    "Camera" -> s.convertCamera(out as KMutableProperty0<Camera?>)
                    "World" -> s.convertWorld(out as KMutableProperty0<World?>)
                    "Base" -> s.convertBase(out as KMutableProperty0<Base?>)
                    else -> throw Error("type invalid ${f.type}")
                }

                db.reader.pos = pOld
            }
        }

        if (!ASSIMP.BUILD.BLENDER.NO_STATS && out() != null)
            ++db.stats.pointersResolved

        return false
    }

    fun resolvePointer(out: FileOffset?, ptrVal: Long) {
        // Currently used exclusively by PackedFile::data to represent a simple offset into the mapped BLEND file.
        TODO()
//        out.reset();
//        if (!ptrval.val) {
//                    return false;
//                }
//
//                // find the file block the pointer is pointing to
//                const FileBlockHead* block = LocateFileBlockForAddress(ptrval,db);
//
//        out =  std::shared_ptr< FileOffset > (new FileOffset());
//        out->val = block->start+ static_cast<size_t>((ptrval.val - block->address.val) );
//        return false;
    }

    fun <T> resolvePointer(out: ArrayList<T>, ptrVal: Long, f: Field): Boolean {
        /*  This is a function overload, not a template specialization. According to the partial ordering rules, it
            should be selected by the compiler for array-of-pointer inputs, i.e. Object::mats.  */

        TODO()
//        out.reset();
//        if (!ptrval.val) {
//                    return false;
//                }
//
//                // find the file block the pointer is pointing to
//                const FileBlockHead* block = LocateFileBlockForAddress(ptrval,db);
//        const size_t num = block->size / (db.i64bit?8:4);
//
//        // keep the old stream position
//        const StreamReaderAny::pos pold = db.reader->GetCurrentPos();
//        db.reader->SetCurrentPos(block->start+ static_cast<size_t>((ptrval.val - block->address.val) ));
//
//        bool res = false;
//        // allocate raw storage for the array
//        out.resize(num);
//        for (size_t i = 0; i< num; ++i) {
//        Pointer val;
//        Convert(val,db);
//
//        // and resolve the pointees
//        res = ResolvePointer(out[i],val,db,f) && res;
//    }
//
//        db.reader->SetCurrentPos(pold);
//        return res;
    }

    fun resolvePointer(out: KMutableProperty0<ElemBase?>, ptrVal: Long): Boolean {

        isElem = false
        /*  Special case when the data type needs to be determined at runtime.
            Less secure than in the `strongly-typed` case.         */

        out.set(null)
        if (ptrVal == 0L) return false

        // find the file block the pointer is pointing to
        val block = locateFileBlockForAddress(ptrVal)

        // determine the target type from the block header
        val s = db.dna[block.dnaIndex.L]

        // try to retrieve the object from the cache
        db.cache.get(s, out, ptrVal)
        if (out() != null) return true

        // seek to this location, but save the previous stream pointer.
        val pOld = db.reader.pos
        db.reader.pos = block.start + (ptrVal - block.address).i
        // FIXME: basically, this could cause problems with 64 bit pointers on 32 bit systems.
        // I really ought to improve StreamReader to work with 64 bit indices exclusively.

        // continue conversion after allocating the required storage
        val builders = db.dna.getBlobToStructureConverter(s)
        if (builders.first == null) {
            /*  this might happen if DNA::RegisterConverters hasn't been called so far or
                if the target type is not contained in `our` DNA.             */
            out.set(null)
            logger.warn("Failed to find a converter for the `${s.name}` structure")
            return false
        }

        val first = builders.first as KFunction0<ElemBase>
        // allocate the object hull
        out.set(first())

        /*  cache the object immediately to prevent infinite recursion in a circular list with a single element
            (i.e. a self-referencing element).         */
        db.cache.set(s, out, ptrVal)

        // and do the actual conversion
        val second = builders.second as Structure.(KMutableProperty0<ElemBase?>) -> Unit
        s.second(out)
        db.reader.pos = pOld

        /*  store a pointer to the name string of the actual type in the object itself. This allows the conversion code
            to perform additional type checking.         */
        out()!!.dnaType = s.name

        if (!ASSIMP.BUILD.BLENDER.NO_STATS) ++db.stats.pointersResolved

        return false
    }

    fun locateFileBlockForAddress(ptrVal: Long): FileBlockHead {

        /*  the file blocks appear in list sorted by with ascending base addresses so we can run a binary search to locate
            the pointer quickly.

            NOTE: Blender seems to distinguish between side-by-side data (stored in the same data block) and far pointers,
            which are only used for structures starting with an ID.
            We don't need to make this distinction, our algorithm works regardless where the data is stored.    */
        val it = db.entries.firstOrNull { it.address >= ptrVal } ?:
        /*  This is crucial, pointers may not be invalid. This is either a corrupted file or an attempted attack.   */
        throw Error("Failure resolving pointer 0x${ptrVal.toHexString}, no file block falls into this address range")
        if (ptrVal >= it.address + it.size)
            throw Error("Failure resolving pointer 0x${ptrVal.toHexString}, nearest file block starting at " +
                    "0x${it.address.toHexString} ends at 0x${(it.address + it.size).toHexString}")
        return it
    }
//
//    private :
//
//    // ------------------------------------------------------------------------------
//    template <typename T> T* _allocate(std::shared_ptr<T>& out , size_t& s)
//    const {
//        out = std::shared_ptr<T>(new T ())
//        s = 1
//        return out.get()
//    }
//
//    template <typename T> T* _allocate(vector<T>& out , size_t& s)
//    const {
//        out.resize(s)
//        return s ? &out.front() : NULL
//    }
//
//    // --------------------------------------------------------
//    template <int error_policy>
//    struct _defaultInitializer
//    {
//
//        template < typename T, unsigned int N>
//        void operator ()(T(& out)[N], const char* = NULL) {
//        for (unsigned int i = 0; i < N; ++i) {
//        out[i] = T()
//    }
//    }
//
//        template < typename T, unsigned int N, unsigned int M>
//        void operator ()(T(& out)[N][M], const char* = NULL) {
//        for (unsigned int i = 0; i < N; ++i) {
//        for (unsigned int j = 0; j < M; ++j) {
//        out[i][j] = T()
//    }
//    }
//    }
//
//        template < typename T >
//        void operator ()(T& out, const char* = NULL) {
//        out = T()
//    }
//    }

    fun convertObject(dest: KMutableProperty0<Object?>) {

        val d = dest() ?: Object().also { dest.set(it) }

        readField(Ep.Fail, d.id, "id")
        readField(Ep.Fail, ::e, "type")
        d.type = Object.Type of e
        readFieldArray2(Ep.Warn, d.obmat, "obmat")
        readFieldArray2(Ep.Warn, d.parentinv, "parentinv")
        d.parSubstr = readFieldString(Ep.Warn, "parsubstr")
        readFieldPtr(Ep.Warn, d::parent, "*parent")
        readFieldPtr(Ep.Warn, d::track, "*track")
        readFieldPtr(Ep.Warn, d::proxy, "*proxy")
        readFieldPtr(Ep.Warn, d::proxyFrom, "*proxy_from")
        readFieldPtr(Ep.Warn, d::proxyGroup, "*proxy_group")
        readFieldPtr(Ep.Warn, d::dupGroup, "*dup_group")
        isElem = true
        readFieldPtr(Ep.Fail, d::data, "*data")
        readField(Ep.Igno, d.modifiers, "modifiers")

        db.reader.pos += size.i
    }

    fun convertGroup(dest: KMutableProperty0<Group?>) {

        val d = dest() ?: Group().also { dest.set(it) }

        readField(Ep.Fail, d.id, "id")
        readField(Ep.Igno, d.layer, "layer")
        readFieldPtr(Ep.Igno, d::gObject, "*gobject")

        db.reader.pos += size.i
    }

    fun convertMTex(dest: KMutableProperty0<MTex?>) {

        val d = dest() ?: MTex().also { dest.set(it) }

        readField(Ep.Igno, ::e, "mapto")
        d.mapTo = MTex.MapType of e
        readField(Ep.Igno, ::e, "blendtype")
        d.blendType = MTex.BlendType of e
        readFieldPtr(Ep.Igno, d::object_, "*object")
        readFieldPtr(Ep.Igno, d::tex, "*tex")
        d.uvName = readFieldString(Ep.Igno, "uvname")
        readField(Ep.Igno, ::e, "projx")
        d.projX = MTex.Projection of e
        readField(Ep.Igno, ::e, "projy")
        d.projY = MTex.Projection of e
        readField(Ep.Igno, ::e, "projz")
        d.projZ = MTex.Projection of e
        d.mapping = readFieldString(Ep.Igno, "mapping")
        readFieldFloatArray(Ep.Igno, d.ofs, "ofs")
        readFieldFloatArray(Ep.Igno, d.size, "size")
        readField(Ep.Igno, d::rot, "rot")
        readField(Ep.Igno, d::texFlag, "texflag")
        readField(Ep.Igno, d::colorModel, "colormodel")
        readField(Ep.Igno, d::pMapTo, "pmapto")
        readField(Ep.Igno, d::pMapToNeg, "pmaptoneg")
        readField(Ep.Warn, d::r, "r")
        readField(Ep.Warn, d::g, "g")
        readField(Ep.Warn, d::b, "b")
        readField(Ep.Warn, d::k, "k")
        readField(Ep.Igno, d::colSpecFac, "colspecfac")
        readField(Ep.Igno, d::mirrFac, "mirrfac")
        readField(Ep.Igno, d::alphaFac, "alphafac")
        readField(Ep.Igno, d::diffFac, "difffac")
        readField(Ep.Igno, d::specFac, "specfac")
        readField(Ep.Igno, d::emitFac, "emitfac")
        readField(Ep.Igno, d::hardFac, "hardfac")
        readField(Ep.Igno, d::norFac, "norfac")

        db.reader.pos += size.i
    }

    fun convertTFace(dest: KMutableProperty0<TFace?>) {

        val d = dest() ?: TFace().also { dest.set(it) }

        readFieldArray2(Ep.Fail, d.uv, "uv")
        readFieldIntArray(Ep.Fail, d.col, "col")
        readField(Ep.Igno, d::flag, "flag")
        readField(Ep.Igno, d::mode, "mode")
        readField(Ep.Igno, d::tile, "tile")
        readField(Ep.Igno, d::unwrap, "unwrap")

        db.reader.pos += size.i
    }

    fun convertSubsurfModifierData(dest: KMutableProperty0<SubsurfModifierData?>) {

        val d = dest() ?: SubsurfModifierData().also { dest.set(it) }

        readField(Ep.Fail, d.modifier, "modifier")
        readField(Ep.Warn, d::subdivType, "subdivType")
        readField(Ep.Fail, d::levels, "levels")
        readField(Ep.Igno, d::renderLevels, "renderLevels")
        readField(Ep.Igno, d::flags, "flags")

        db.reader.pos += size.i
    }

    fun convertMFace(dest: KMutableProperty0<MFace? >) {

        val d = dest() ?: MFace().also { dest.set(it) }

        readField(Ep.Fail, d::v1, "v1")
        readField(Ep.Fail, d::v2, "v2")
        readField(Ep.Fail, d::v3, "v3")
        readField(Ep.Fail, d::v4, "v4")
        readField(Ep.Fail, d::matNr, "mat_nr")
        readField(Ep.Igno, d::flag, "flag")

        db.reader.pos += size.i
    }

    fun convertLamp(dest: KMutableProperty0<Lamp?>) {

        val d = dest() ?: Lamp().also { dest.set(it) }

        readField(Ep.Fail, d::id,"id")
        readField(Ep.Fail, ::e,"type")
        d.type = Lamp.Type of e
        readField(Ep.Igno, d::flags,"flags")
        readField(Ep.Igno, d::colorModel,"colormodel")
        readField(Ep.Igno, d::totex,"totex")
        readField(Ep.Warn, d::r,"r")
        readField(Ep.Warn, d::g,"g")
        readField(Ep.Warn, d::b,"b")
        readField(Ep.Warn, d::k,"k")
        readField(Ep.Igno, d::energy,"energy")
        readField(Ep.Igno, d::dist,"dist")
        readField(Ep.Igno, d::spotSize,"spotsize")
        readField(Ep.Igno, d::spotBlend,"spotblend")
        readField(Ep.Igno, d::att1,"att1")
        readField(Ep.Igno, d::att2,"att2")
        readField(Ep.Igno, ::e,"falloff_type")
        d.falloffType = Lamp.FalloffType of e
        readField(Ep.Igno, d::sunBrightness,"sun_brightness")
        readField(Ep.Igno, d::areaSize,"area_size")
        readField(Ep.Igno, d::areaSizeY,"area_sizey")
        readField(Ep.Igno, d::areaSizeZ,"area_sizez")
        readField(Ep.Igno, d::areaShape,"area_shape")

        db.reader.pos += size.i
    }

    fun convertMDeformWeight(dest: KMutableProperty0<MDeformWeight?>) {

        val d = dest() ?: MDeformWeight().also { dest.set(it) }

        readField(Ep.Fail, d::defNr,"def_nr")
        readField(Ep.Fail, d::weight,"weight")

        db.reader.pos += size.i
    }

    fun convertPackedFile(dest: KMutableProperty0<PackedFile?>) {

        val d = dest() ?: PackedFile().also { dest.set(it) }

        readField(Ep.Warn, d::size,"size")
        readField(Ep.Warn, d::seek,"seek")
        readFieldPtr(Ep.Warn, d::data,"*data")

        db.reader.pos += size.i
    }


    fun convertBase(dest: KMutableProperty0<Base?>) {
        /*  note: as per https://github.com/assimp/assimp/issues/128, reading the Object linked list recursively is
            prone to stack overflow.
            This structure converter is therefore an hand-written exception that does it iteratively.   */

        val initialPos = db.reader.pos

        var todo = (dest() ?: Base().also { dest.set(it) }) to initialPos
        while (true) {

            val curDest = todo.first
            db.reader.pos = todo.second

            /*  we know that this is a double-linked, circular list which we never traverse backwards,
                so don't bother resolving the back links.             */
            curDest.prev = null

            readFieldPtr(Ep.Warn, curDest::object_, "*object")

            /*  the return value of ReadFieldPtr indicates whether the object was already cached.
                In this case, we don't need to resolve it again.    */
            if (!readFieldPtr(Ep.Warn, curDest::next, "*next", true) && curDest.next != null) {
                todo = (curDest.next ?: Base().also { curDest.next = it }) to db.reader.pos
                continue
            }
            break
        }

        db.reader.pos = initialPos + size.i
    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MTFace> (
//    MTFace& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadFieldArray2<ErrorPolicy_Fail>(dest.uv,"uv",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//        ReadField<ErrorPolicy_Igno>(dest.mode,"mode",db);
//        ReadField<ErrorPolicy_Igno>(dest.tile,"tile",db);
//        ReadField<ErrorPolicy_Igno>(dest.unwrap,"unwrap",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<Material> (
//    Material& dest,
//    const FileDatabase& db
//    ) const
//    {
//        ReadField<ErrorPolicy_Fail>(dest.id,"id",db);
//        ReadField<ErrorPolicy_Warn>(dest.r,"r",db);
//        ReadField<ErrorPolicy_Warn>(dest.g,"g",db);
//        ReadField<ErrorPolicy_Warn>(dest.b,"b",db);
//        ReadField<ErrorPolicy_Warn>(dest.specr,"specr",db);
//        ReadField<ErrorPolicy_Warn>(dest.specg,"specg",db);
//        ReadField<ErrorPolicy_Warn>(dest.specb,"specb",db);
//        ReadField<ErrorPolicy_Igno>(dest.har,"har",db);
//        ReadField<ErrorPolicy_Warn>(dest.ambr,"ambr",db);
//        ReadField<ErrorPolicy_Warn>(dest.ambg,"ambg",db);
//        ReadField<ErrorPolicy_Warn>(dest.ambb,"ambb",db);
//        ReadField<ErrorPolicy_Igno>(dest.mirr,"mirr",db);
//        ReadField<ErrorPolicy_Igno>(dest.mirg,"mirg",db);
//        ReadField<ErrorPolicy_Igno>(dest.mirb,"mirb",db);
//        ReadField<ErrorPolicy_Warn>(dest.emit,"emit",db);
//        ReadField<ErrorPolicy_Igno>(dest.ray_mirror,"ray_mirror",db);
//        ReadField<ErrorPolicy_Warn>(dest.alpha,"alpha",db);
//        ReadField<ErrorPolicy_Igno>(dest.ref,"ref",db);
//        ReadField<ErrorPolicy_Igno>(dest.translucency,"translucency",db);
//        ReadField<ErrorPolicy_Igno>(dest.mode,"mode",db);
//        ReadField<ErrorPolicy_Igno>(dest.roughness,"roughness",db);
//        ReadField<ErrorPolicy_Igno>(dest.darkness,"darkness",db);
//        ReadField<ErrorPolicy_Igno>(dest.refrac,"refrac",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.group,"*group",db);
//        ReadField<ErrorPolicy_Warn>(dest.diff_shader,"diff_shader",db);
//        ReadField<ErrorPolicy_Warn>(dest.spec_shader,"spec_shader",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mtex,"*mtex",db);
//
//
//        ReadField<ErrorPolicy_Igno>(dest.amb, "amb", db);
//        ReadField<ErrorPolicy_Igno>(dest.ang, "ang", db);
//        ReadField<ErrorPolicy_Igno>(dest.spectra, "spectra", db);
//        ReadField<ErrorPolicy_Igno>(dest.spec, "spec", db);
//        ReadField<ErrorPolicy_Igno>(dest.zoffs, "zoffs", db);
//        ReadField<ErrorPolicy_Igno>(dest.add, "add", db);
//        ReadField<ErrorPolicy_Igno>(dest.fresnel_mir, "fresnel_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.fresnel_mir_i, "fresnel_mir_i", db);
//        ReadField<ErrorPolicy_Igno>(dest.fresnel_tra, "fresnel_tra", db);
//        ReadField<ErrorPolicy_Igno>(dest.fresnel_tra_i, "fresnel_tra_i", db);
//        ReadField<ErrorPolicy_Igno>(dest.filter, "filter", db);
//        ReadField<ErrorPolicy_Igno>(dest.tx_limit, "tx_limit", db);
//        ReadField<ErrorPolicy_Igno>(dest.tx_falloff, "tx_falloff", db);
//        ReadField<ErrorPolicy_Igno>(dest.gloss_mir, "gloss_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.gloss_tra, "gloss_tra", db);
//        ReadField<ErrorPolicy_Igno>(dest.adapt_thresh_mir, "adapt_thresh_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.adapt_thresh_tra, "adapt_thresh_tra", db);
//        ReadField<ErrorPolicy_Igno>(dest.aniso_gloss_mir, "aniso_gloss_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.dist_mir, "dist_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.hasize, "hasize", db);
//        ReadField<ErrorPolicy_Igno>(dest.flaresize, "flaresize", db);
//        ReadField<ErrorPolicy_Igno>(dest.subsize, "subsize", db);
//        ReadField<ErrorPolicy_Igno>(dest.flareboost, "flareboost", db);
//        ReadField<ErrorPolicy_Igno>(dest.strand_sta, "strand_sta", db);
//        ReadField<ErrorPolicy_Igno>(dest.strand_end, "strand_end", db);
//        ReadField<ErrorPolicy_Igno>(dest.strand_ease, "strand_ease", db);
//        ReadField<ErrorPolicy_Igno>(dest.strand_surfnor, "strand_surfnor", db);
//        ReadField<ErrorPolicy_Igno>(dest.strand_min, "strand_min", db);
//        ReadField<ErrorPolicy_Igno>(dest.strand_widthfade, "strand_widthfade", db);
//        ReadField<ErrorPolicy_Igno>(dest.sbias, "sbias", db);
//        ReadField<ErrorPolicy_Igno>(dest.lbias, "lbias", db);
//        ReadField<ErrorPolicy_Igno>(dest.shad_alpha, "shad_alpha", db);
//        ReadField<ErrorPolicy_Igno>(dest.param, "param", db);
//        ReadField<ErrorPolicy_Igno>(dest.rms, "rms", db);
//        ReadField<ErrorPolicy_Igno>(dest.rampfac_col, "rampfac_col", db);
//        ReadField<ErrorPolicy_Igno>(dest.rampfac_spec, "rampfac_spec", db);
//        ReadField<ErrorPolicy_Igno>(dest.friction, "friction", db);
//        ReadField<ErrorPolicy_Igno>(dest.fh, "fh", db);
//        ReadField<ErrorPolicy_Igno>(dest.reflect, "reflect", db);
//        ReadField<ErrorPolicy_Igno>(dest.fhdist, "fhdist", db);
//        ReadField<ErrorPolicy_Igno>(dest.xyfrict, "xyfrict", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_radius, "sss_radius", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_col, "sss_col", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_error, "sss_error", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_scale, "sss_scale", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_ior, "sss_ior", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_colfac, "sss_colfac", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_texfac, "sss_texfac", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_front, "sss_front", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_back, "sss_back", db);
//
//        ReadField<ErrorPolicy_Igno>(dest.material_type, "material_type", db);
//        ReadField<ErrorPolicy_Igno>(dest.flag, "flag", db);
//        ReadField<ErrorPolicy_Igno>(dest.ray_depth, "ray_depth", db);
//        ReadField<ErrorPolicy_Igno>(dest.ray_depth_tra, "ray_depth_tra", db);
//        ReadField<ErrorPolicy_Igno>(dest.samp_gloss_mir, "samp_gloss_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.samp_gloss_tra, "samp_gloss_tra", db);
//        ReadField<ErrorPolicy_Igno>(dest.fadeto_mir, "fadeto_mir", db);
//        ReadField<ErrorPolicy_Igno>(dest.shade_flag, "shade_flag", db);
//        ReadField<ErrorPolicy_Igno>(dest.flarec, "flarec", db);
//        ReadField<ErrorPolicy_Igno>(dest.starc, "starc", db);
//        ReadField<ErrorPolicy_Igno>(dest.linec, "linec", db);
//        ReadField<ErrorPolicy_Igno>(dest.ringc, "ringc", db);
//        ReadField<ErrorPolicy_Igno>(dest.pr_lamp, "pr_lamp", db);
//        ReadField<ErrorPolicy_Igno>(dest.pr_texture, "pr_texture", db);
//        ReadField<ErrorPolicy_Igno>(dest.ml_flag, "ml_flag", db);
//        ReadField<ErrorPolicy_Igno>(dest.diff_shader, "diff_shader", db);
//        ReadField<ErrorPolicy_Igno>(dest.spec_shader, "spec_shader", db);
//        ReadField<ErrorPolicy_Igno>(dest.texco, "texco", db);
//        ReadField<ErrorPolicy_Igno>(dest.mapto, "mapto", db);
//        ReadField<ErrorPolicy_Igno>(dest.ramp_show, "ramp_show", db);
//        ReadField<ErrorPolicy_Igno>(dest.pad3, "pad3", db);
//        ReadField<ErrorPolicy_Igno>(dest.dynamode, "dynamode", db);
//        ReadField<ErrorPolicy_Igno>(dest.pad2, "pad2", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_flag, "sss_flag", db);
//        ReadField<ErrorPolicy_Igno>(dest.sss_preset, "sss_preset", db);
//        ReadField<ErrorPolicy_Igno>(dest.shadowonly_flag, "shadowonly_flag", db);
//        ReadField<ErrorPolicy_Igno>(dest.index, "index", db);
//        ReadField<ErrorPolicy_Igno>(dest.vcol_alpha, "vcol_alpha", db);
//        ReadField<ErrorPolicy_Igno>(dest.pad4, "pad4", db);
//
//        ReadField<ErrorPolicy_Igno>(dest.seed1, "seed1", db);
//        ReadField<ErrorPolicy_Igno>(dest.seed2, "seed2", db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MTexPoly> (
//    MTexPoly& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        {
//            std::shared_ptr<Image> tpage;
//            ReadFieldPtr<ErrorPolicy_Igno>(tpage,"*tpage",db);
//            dest.tpage = tpage.get();
//        }
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//        ReadField<ErrorPolicy_Igno>(dest.transp,"transp",db);
//        ReadField<ErrorPolicy_Igno>(dest.mode,"mode",db);
//        ReadField<ErrorPolicy_Igno>(dest.tile,"tile",db);
//        ReadField<ErrorPolicy_Igno>(dest.pad,"pad",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<Mesh> (
//    Mesh& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Fail>(dest.id,"id",db);
//        ReadField<ErrorPolicy_Fail>(dest.totface,"totface",db);
//        ReadField<ErrorPolicy_Fail>(dest.totedge,"totedge",db);
//        ReadField<ErrorPolicy_Fail>(dest.totvert,"totvert",db);
//        ReadField<ErrorPolicy_Igno>(dest.totloop,"totloop",db);
//        ReadField<ErrorPolicy_Igno>(dest.totpoly,"totpoly",db);
//        ReadField<ErrorPolicy_Igno>(dest.subdiv,"subdiv",db);
//        ReadField<ErrorPolicy_Igno>(dest.subdivr,"subdivr",db);
//        ReadField<ErrorPolicy_Igno>(dest.subsurftype,"subsurftype",db);
//        ReadField<ErrorPolicy_Igno>(dest.smoothresh,"smoothresh",db);
//        ReadFieldPtr<ErrorPolicy_Fail>(dest.mface,"*mface",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mtface,"*mtface",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.tface,"*tface",db);
//        ReadFieldPtr<ErrorPolicy_Fail>(dest.mvert,"*mvert",db);
//        ReadFieldPtr<ErrorPolicy_Warn>(dest.medge,"*medge",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mloop,"*mloop",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mloopuv,"*mloopuv",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mloopcol,"*mloopcol",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mpoly,"*mpoly",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mtpoly,"*mtpoly",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.dvert,"*dvert",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mcol,"*mcol",db);
//        ReadFieldPtr<ErrorPolicy_Fail>(dest.mat,"**mat",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MDeformVert> (
//    MDeformVert& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadFieldPtr<ErrorPolicy_Warn>(dest.dw,"*dw",db);
//        ReadField<ErrorPolicy_Igno>(dest.totweight,"totweight",db);
//
//        db.reader->IncPtr(size);
//    }
//

    fun convertWorld(dest: KMutableProperty0<World?>) {

        val d = dest() ?: World().also { dest.set(it) }

        readField(Ep.Fail, d.id, "id")

        db.reader.pos += size.i
    }

////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MLoopCol> (
//    MLoopCol& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Igno>(dest.r,"r",db);
//        ReadField<ErrorPolicy_Igno>(dest.g,"g",db);
//        ReadField<ErrorPolicy_Igno>(dest.b,"b",db);
//        ReadField<ErrorPolicy_Igno>(dest.a,"a",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MVert> (
//    MVert& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadFieldArray<ErrorPolicy_Fail>(dest.co,"co",db);
//        ReadFieldArray<ErrorPolicy_Fail>(dest.no,"no",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//        //ReadField<ErrorPolicy_Warn>(dest.mat_nr,"mat_nr",db);
//        ReadField<ErrorPolicy_Igno>(dest.bweight,"bweight",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MEdge> (
//    MEdge& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Fail>(dest.v1,"v1",db);
//        ReadField<ErrorPolicy_Fail>(dest.v2,"v2",db);
//        ReadField<ErrorPolicy_Igno>(dest.crease,"crease",db);
//        ReadField<ErrorPolicy_Igno>(dest.bweight,"bweight",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MLoopUV> (
//    MLoopUV& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadFieldArray<ErrorPolicy_Igno>(dest.uv,"uv",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//
//        db.reader->IncPtr(size);
//    }
//

    fun convertGroupObject(dest: KMutableProperty0<GroupObject?>) {

        val d = dest() ?: GroupObject().also { dest.set(it) }

        readFieldPtr(Ep.Fail, d::prev, "*prev")
        readFieldPtr(Ep.Fail, d::next, "*next")
        readFieldPtr(Ep.Igno, d::ob, "*ob")

        db.reader.pos += size.i
    }


    fun convert(dest: ListBase) {

        isElem = true
        readFieldPtr(Ep.Igno, dest::first, "*first")
        isElem = true
        readFieldPtr(Ep.Igno, dest::last, "*last")

        db.reader.pos += size.i
    }

////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MLoop> (
//    MLoop& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Igno>(dest.v,"v",db);
//        ReadField<ErrorPolicy_Igno>(dest.e,"e",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<ModifierData> (
//    ModifierData& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadFieldPtr<ErrorPolicy_Warn>(dest.next,"*next",db);
//        ReadFieldPtr<ErrorPolicy_Warn>(dest.prev,"*prev",db);
//        ReadField<ErrorPolicy_Igno>(dest.type,"type",db);
//        ReadField<ErrorPolicy_Igno>(dest.mode,"mode",db);
//        ReadFieldArray<ErrorPolicy_Igno>(dest.name,"name",db);
//
//        db.reader->IncPtr(size);
//    }
//

    fun convert(id: Id) {

        id.name = readFieldString(Ep.Warn, "name")
        readField(Ep.Igno, id::flag, "flag")

        db.reader.pos += size.i
    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MCol> (
//    MCol& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Fail>(dest.r,"r",db);
//        ReadField<ErrorPolicy_Fail>(dest.g,"g",db);
//        ReadField<ErrorPolicy_Fail>(dest.b,"b",db);
//        ReadField<ErrorPolicy_Fail>(dest.a,"a",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MPoly> (
//    MPoly& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Igno>(dest.loopstart,"loopstart",db);
//        ReadField<ErrorPolicy_Igno>(dest.totloop,"totloop",db);
//        ReadField<ErrorPolicy_Igno>(dest.mat_nr,"mat_nr",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//
//        db.reader->IncPtr(size);
//    }
//

    fun convertScene(): Scene {

        val dest = Scene()

        readField(Ep.Fail, dest.id, "id")
        readFieldPtr(Ep.Warn, dest::camera, "*camera")
        readFieldPtr(Ep.Warn, dest::world, "*world")
        readFieldPtr(Ep.Warn, dest::basact, "*basact")
        readField(Ep.Igno, dest.base, "base")

        db.reader.pos += size.i

        return dest
    }

    //
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<Library> (
//    Library& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Fail>(dest.id,"id",db);
//        ReadFieldArray<ErrorPolicy_Warn>(dest.name,"name",db);
//        ReadFieldArray<ErrorPolicy_Fail>(dest.filename,"filename",db);
//        ReadFieldPtr<ErrorPolicy_Warn>(dest.parent,"*parent",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<Tex> (
//    Tex& dest,
//    const FileDatabase& db
//    ) const
//    {
//        short temp_short = 0;
//        ReadField<ErrorPolicy_Igno>(temp_short,"imaflag",db);
//        dest.imaflag = static_cast<Assimp::Blender::Tex::ImageFlags>(temp_short);
//        int temp = 0;
//        ReadField<ErrorPolicy_Fail>(temp,"type",db);
//        dest.type = static_cast<Assimp::Blender::Tex::Type>(temp);
//        ReadFieldPtr<ErrorPolicy_Warn>(dest.ima,"*ima",db);
//
//        db.reader->IncPtr(size);
//    }
//
    fun convertCamera(dest: KMutableProperty0<Camera?>) {

        val d = dest() ?: Camera().also { dest.set(it) }

        readField(Ep.Fail, d.id, "id")
        readField(Ep.Warn, ::e, "type")
        d.type = Camera.Type of e
        readField(Ep.Warn, ::e, "flag")
        d.flag = Camera.Type of e
        readField(Ep.Warn, d::lens, "lens")
        readField(Ep.Warn, d::sensorX, "sensor_x")
        readField(Ep.Igno, d::clipSta, "clipsta")
        readField(Ep.Igno, d::clipEnd, "clipend")

        db.reader.pos += size.i
    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<MirrorModifierData> (
//    MirrorModifierData& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Fail>(dest.modifier,"modifier",db);
//        ReadField<ErrorPolicy_Igno>(dest.axis,"axis",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//        ReadField<ErrorPolicy_Igno>(dest.tolerance,"tolerance",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.mirror_ob,"*mirror_ob",db);
//
//        db.reader->IncPtr(size);
//    }
//
////--------------------------------------------------------------------------------
//    template <> void Structure :: Convert<Image> (
//    Image& dest,
//    const FileDatabase& db
//    ) const
//    {
//
//        ReadField<ErrorPolicy_Fail>(dest.id,"id",db);
//        ReadFieldArray<ErrorPolicy_Warn>(dest.name,"name",db);
//        ReadField<ErrorPolicy_Igno>(dest.ok,"ok",db);
//        ReadField<ErrorPolicy_Igno>(dest.flag,"flag",db);
//        ReadField<ErrorPolicy_Igno>(dest.source,"source",db);
//        ReadField<ErrorPolicy_Igno>(dest.type,"type",db);
//        ReadField<ErrorPolicy_Igno>(dest.pad,"pad",db);
//        ReadField<ErrorPolicy_Igno>(dest.pad1,"pad1",db);
//        ReadField<ErrorPolicy_Igno>(dest.lastframe,"lastframe",db);
//        ReadField<ErrorPolicy_Igno>(dest.tpageflag,"tpageflag",db);
//        ReadField<ErrorPolicy_Igno>(dest.totbind,"totbind",db);
//        ReadField<ErrorPolicy_Igno>(dest.xrep,"xrep",db);
//        ReadField<ErrorPolicy_Igno>(dest.yrep,"yrep",db);
//        ReadField<ErrorPolicy_Igno>(dest.twsta,"twsta",db);
//        ReadField<ErrorPolicy_Igno>(dest.twend,"twend",db);
//        ReadFieldPtr<ErrorPolicy_Igno>(dest.packedfile,"*packedfile",db);
//        ReadField<ErrorPolicy_Igno>(dest.lastupdate,"lastupdate",db);
//        ReadField<ErrorPolicy_Igno>(dest.lastused,"lastused",db);
//        ReadField<ErrorPolicy_Igno>(dest.animspeed,"animspeed",db);
//        ReadField<ErrorPolicy_Igno>(dest.gen_x,"gen_x",db);
//        ReadField<ErrorPolicy_Igno>(dest.gen_y,"gen_y",db);
//        ReadField<ErrorPolicy_Igno>(dest.gen_type,"gen_type",db);
//
//        db.reader->IncPtr(size);
//    }

    companion object {
        var e = 0
        var isElem = false
    }
}

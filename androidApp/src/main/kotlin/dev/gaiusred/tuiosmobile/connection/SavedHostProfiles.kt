package dev.gaiusred.tuiosmobile.connection

import android.content.Context
import android.util.AtomicFile
import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Host settings have their own file; history pruning cannot delete pins or identities. */
class SavedHostProfiles(context: Context) : HostProfileStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "hosts.json"))
    private val gate = Mutex()
    private fun read(): List<HostProfile> {
        if (!file.baseFile.exists()) return emptyList()
        require(file.baseFile.length() <= 262_144)
        return Json.decodeFromString<List<HostProfile>>(String(file.readFully(), Charsets.UTF_8)).also {
            require(it.size <= 100 && it.map { host -> host.id }.distinct().size == it.size)
        }
    }
    override suspend fun list(): List<HostProfile> = withContext(Dispatchers.IO) { gate.withLock { read() } }
    override suspend fun save(profile: HostProfile) = withContext(Dispatchers.IO) {
        gate.withLock {
            parseHostKey(profile.hostKey)
            require(profile.sshCandidates.size in 1..16)
            val profiles = read().filterNot { it.id == profile.id } + profile
            require(profiles.size <= 100)
            write(profiles)
        }
    }
    override suspend fun remove(id: HostId) = withContext(Dispatchers.IO) {
        gate.withLock { write(read().filterNot { it.id == id }) }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) { gate.withLock { write(emptyList()) } }
    private fun write(profiles: List<HostProfile>) {
        val bytes = Json.encodeToString(profiles).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262_144)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}

package dev.gaiusred.tuiosmobile.connection

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class HostKeyStoreTest {
    @Test fun changedHostKeyRequiresExplicitReview() {
        val type = "ssh-ed25519".toByteArray()
        val pinned = ByteBuffer.allocate(4 + type.size + 4 + 32).putInt(type.size).put(type).putInt(32).put(ByteArray(32) { 1 }).array()
        val repo = HostKeyStore(HostEndpoint("100.100.1.1", 2222), PublicHostKey("ssh-ed25519", pinned))
        assertEquals(HostKeyRepository.OK, repo.check("[100.100.1.1]:2222", pinned))
        val changed = pinned.copyOf().also { it[it.lastIndex] = 2 }
        assertEquals(HostKeyRepository.CHANGED, repo.check("[100.100.1.1]:2222", changed))
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("[100.100.1.2]:2222", pinned))
    }
}

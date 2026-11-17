// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

// JVM unit tests for RemotePolicyState (the remote-policy seam). No
// Robolectric: SharedPreferences is an interface, so a hand-rolled fake
// drives every behavior. android.util.Log.i throws "Stub!" on the host
// JVM, but every logging call inside RemotePolicyState is wrapped in a
// total try/catch, so the tests exercise the real logging paths.
//
// RemotePolicyState is a process-wide object with @Volatile state; tests
// reset `initialized`/`snapshot` via reflection around each test so cases
// are independent of execution order.
class RemotePolicyStateTest {

    @Before
    fun setUp() = resetRemotePolicyState()

    @After
    fun tearDown() = resetRemotePolicyState()

    private fun resetRemotePolicyState() {
        setPrivateField("initialized", false)
        setPrivateField("snapshot", emptySet<String>())
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = RemotePolicyState::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(RemotePolicyState, value)
    }

    private fun primeInitialized(fake: FakeSharedPreferences, stored: Set<String>) {
        fake.stored = stored
        RemotePolicyState.ensureInitialized { fake }
    }

    @Test
    fun listenerBodyNeverThrowsWhenGetStringSetThrows() {
        val fake = FakeSharedPreferences()
        primeInitialized(fake, setOf("com.old.app"))
        fake.getStringSetError = RuntimeException("binder gone (transient)")
        try {
            fake.fire("packages")
        } catch (t: Throwable) {
            fail("listener must never unwind into the daemon; got $t")
        }
        assertEquals(setOf("com.old.app"), RemotePolicyState.get())
    }

    @Test
    fun getStringSetReturningNullYieldsEmptySet() {
        val fake = FakeSharedPreferences()
        primeInitialized(fake, setOf("com.old.app"))
        fake.getStringSetReturnsNull = true // binder hands us null
        fake.fire("packages")
        assertEquals(emptySet<String>(), RemotePolicyState.get())
    }

    @Test
    fun listenerWithGarbageElementsDampenedValidKeptNoThrow() {
        val fake = FakeSharedPreferences()
        primeInitialized(fake, setOf("com.seed.app"))
        // Garbage: non-String element, null element, invalid package names.
        fake.stored = setOf(42, "com.valid.app", "bad pkg!", null, "semi;colon")
        try {
            fake.fire("packages")
        } catch (t: Throwable) {
            fail("listener must never throw on garbage input; got $t")
        }
        assertEquals(setOf("com.valid.app"), RemotePolicyState.get())
    }

    @Test
    fun updateReplacesSnapshotWholesale() {
        val fake = FakeSharedPreferences()
        primeInitialized(fake, setOf("com.stale.a", "com.stale.b"))
        fake.stored = linkedSetOf("com.new.app")
        fake.fire("packages")
        // The previous snapshot is gone wholesale, not merged.
        assertEquals(setOf("com.new.app"), RemotePolicyState.get())
    }

    @Test
    fun clearGetStringSetEmptySetYieldsEmptySet() {
        val fake = FakeSharedPreferences()
        primeInitialized(fake, setOf("com.old.app"))
        fake.stored = emptySet<String>() // genuine clear
        fake.fire("packages")
        assertEquals(emptySet<String>(), RemotePolicyState.get())
    }

    @Test
    fun ensureInitializedIsOneShotProviderCalledExactlyOnce() {
        val fake = FakeSharedPreferences()
        var providerCalls = 0
        repeat(3) {
            RemotePolicyState.ensureInitialized {
                providerCalls++
                fake
            }
        }
        assertEquals(1, providerCalls)
    }

    @Test
    fun failureKeepsPreviousSnapshotThenSuccessAfterFailureReplaces() {
        val fake = FakeSharedPreferences()
        primeInitialized(fake, setOf("com.previous.app"))
        // F-01 scenario: a transient read failure keeps the previous snapshot.
        fake.getStringSetError = RuntimeException("transient binder failure")
        fake.fire("packages")
        assertEquals(setOf("com.previous.app"), RemotePolicyState.get())
        // And the next successful read replaces it wholesale.
        fake.getStringSetError = null
        fake.stored = linkedSetOf("com.after.app")
        fake.fire("packages")
        assertEquals(setOf("com.after.app"), RemotePolicyState.get())
    }

    // Hand-rolled in-memory fake: group map semantics via a single backing
    // store, a controllable getStringSet (throwing / null / garbage elements),
    // and a registered-listeners list. No other behavior is provided — every
    // other member throws UnsupportedOperationException.
    private class FakeSharedPreferences : SharedPreferences {

        /** When non-null, getStringSet throws this instead of returning. */
        var getStringSetError: Throwable? = null

        /** When true, getStringSet returns null. */
        var getStringSetReturnsNull: Boolean = false

        /** Backing store; may be assigned garbage (non-String) elements. */
        var stored: Set<*> = emptySet<String>()

        /** Registered listeners, in registration order. */
        val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            getStringSetError?.let { throw it }
            if (getStringSetReturnsNull) return null
            // Binder hands us a fresh java.util.Set copy each time — mirror
            // that, and never hand out the test's own reference (a pure-
            // Kotlin emptySet() is not a runtime MutableSet).
            return LinkedHashSet(stored) as MutableSet<String>
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listeners.remove(listener)
        }

        /** Delivers an update for [key] to every registered listener. */
        fun fire(key: String?) {
            for (listener in listeners) {
                listener.onSharedPreferenceChanged(this, key)
            }
        }

        override fun getAll(): MutableMap<String, Any?> =
            throw UnsupportedOperationException("fake: getAll")

        override fun getString(key: String?, defValue: String?): String? =
            throw UnsupportedOperationException("fake: getString")

        override fun getInt(key: String?, defValue: Int): Int =
            throw UnsupportedOperationException("fake: getInt")

        override fun getLong(key: String?, defValue: Long): Long =
            throw UnsupportedOperationException("fake: getLong")

        override fun getFloat(key: String?, defValue: Float): Float =
            throw UnsupportedOperationException("fake: getFloat")

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            throw UnsupportedOperationException("fake: getBoolean")

        override fun contains(key: String?): Boolean =
            throw UnsupportedOperationException("fake: contains")

        override fun edit(): SharedPreferences.Editor = FakeEditor
    }

    private object FakeEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = throw UnsupportedOperationException("fake: edit")

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun remove(key: String?): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun clear(): SharedPreferences.Editor =
            throw UnsupportedOperationException("fake: edit")

        override fun commit(): Boolean =
            throw UnsupportedOperationException("fake: edit")

        override fun apply() =
            throw UnsupportedOperationException("fake: edit")
    }
}

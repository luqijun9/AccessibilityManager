package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SettingsWriter 纯逻辑单元测试：
 * 重点验证 read-modify-write、外部写入不被旧快照覆盖、读失败不写回、写失败不更新自写标记。
 */
public class SettingsWriterTest {

    private static final ComponentKey A =
            ComponentKey.parse("com.foo/.ServiceA");
    private static final ComponentKey B =
            ComponentKey.parse("com.bar/.ServiceB");
    private static final ComponentKey C =
            ComponentKey.parse("com.baz/.ServiceC");

    private static final class FakeStore implements SettingsWriter.Store {
        String value = "";
        int writeCount;
        boolean failRead;
        boolean nullRead;
        boolean failWrite;

        @Override
        public String read() throws Exception {
            if (failRead) {
                throw new IllegalStateException("read failed");
            }
            if (nullRead) {
                return null;
            }
            return value;
        }

        @Override
        public boolean write(String newValue) throws Exception {
            if (failWrite) {
                return false;
            }
            writeCount++;
            value = newValue;
            return true;
        }
    }

    @Test
    public void applyDelta_addAndRemove_usesLatestValue() {
        FakeStore store = new FakeStore();
        store.value = "com.foo/.ServiceA";
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.singletonList(B),
                java.util.Collections.singletonList(A));

        assertEquals(SettingsWriter.Status.WRITTEN, result.getStatus());
        assertTrue(result.isWritten());
        assertEquals("com.bar/com.bar.ServiceB", store.value);
        assertEquals(1, store.writeCount);
        assertEquals(1L, writer.getWriteGeneration());
        assertTrue(writer.isLastWrittenValue("com.bar/com.bar.ServiceB"));
    }

    @Test
    public void applyDelta_noSemanticChange_returnsUnchangedAndDoesNotWrite() {
        FakeStore store = new FakeStore();
        store.value = "com.foo/.ServiceA";
        SettingsWriter writer = new SettingsWriter(store);

        // 用长形式添加同一服务，规范形式相同，不应产生写入
        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.singletonList(
                        ComponentKey.of("com.foo", "com.foo.ServiceA")),
                java.util.Collections.<ComponentKey>emptyList());

        assertEquals(SettingsWriter.Status.UNCHANGED, result.getStatus());
        assertTrue(result.isUnchanged());
        assertEquals(0, store.writeCount);
        assertEquals("com.foo/.ServiceA", store.value);
        assertNull(writer.getLastWrittenValue());
    }

    @Test
    public void applyDelta_readException_returnsReadFailedAndDoesNotWrite() {
        FakeStore store = new FakeStore();
        store.failRead = true;
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.singletonList(A),
                java.util.Collections.<ComponentKey>emptyList());

        assertEquals(SettingsWriter.Status.READ_FAILED, result.getStatus());
        assertTrue(result.isFailure());
        assertEquals(0, store.writeCount);
        assertNull(writer.getLastWrittenValue());
        assertEquals(0L, writer.getWriteGeneration());
    }

    @Test
    public void applyDelta_nullRead_treatedAsReadFailureToAvoidClearingList() {
        FakeStore store = new FakeStore();
        store.nullRead = true;
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.singletonList(A),
                java.util.Collections.<ComponentKey>emptyList());

        assertEquals(SettingsWriter.Status.READ_FAILED, result.getStatus());
        assertEquals(0, store.writeCount);
        assertNull(writer.getLastWrittenValue());
    }

    @Test
    public void applyDelta_writeFailure_keepsLastWrittenValueAndGeneration() {
        FakeStore store = new FakeStore();
        store.value = "com.foo/.ServiceA";
        store.failWrite = true;
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.singletonList(B),
                java.util.Collections.<ComponentKey>emptyList());

        assertEquals(SettingsWriter.Status.WRITE_FAILED, result.getStatus());
        assertTrue(result.isFailure());
        assertEquals(0, store.writeCount);
        assertEquals("com.foo/.ServiceA", store.value);
        assertNull(writer.getLastWrittenValue());
        assertEquals(0L, writer.getWriteGeneration());
    }

    @Test
    public void applyDelta_externalWriterChange_isRespectedByReRead() {
        FakeStore store = new FakeStore();
        store.value = "com.foo/.ServiceA";
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult first = writer.applyDelta(
                java.util.Collections.singletonList(B),
                java.util.Collections.<ComponentKey>emptyList());
        assertTrue(first.isWritten());
        assertEquals("com.foo/.ServiceA:com.bar/com.bar.ServiceB", store.value);

        // 模拟用户/OEM 在两次写入之间把列表改成了 C
        store.value = "com.baz/.ServiceC";

        SettingsWriter.WriteResult second = writer.applyDelta(
                java.util.Collections.singletonList(B),
                java.util.Collections.singletonList(A));
        assertTrue(second.isWritten());
        // 必须基于最新值 C 追加 B，而不是用旧快照 A、B 覆盖掉 C
        assertEquals("com.baz/.ServiceC:com.bar/com.bar.ServiceB", store.value);
        assertEquals(2L, writer.getWriteGeneration());
        assertTrue(writer.isLastWrittenValue("com.baz/.ServiceC:com.bar/com.bar.ServiceB"));
    }

    @Test
    public void applyDelta_preservesMalformedEntries() {
        FakeStore store = new FakeStore();
        store.value = "invalid-token:com.foo/.ServiceA";
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.singletonList(B),
                java.util.Collections.singletonList(A));

        assertTrue(result.isWritten());
        assertEquals("invalid-token:com.bar/com.bar.ServiceB", store.value);
    }

    @Test
    public void applyDelta_shortLongForm_matchesAndRemovesSameService() {
        FakeStore store = new FakeStore();
        store.value = "com.foo/.ServiceA";
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Collections.<ComponentKey>emptyList(),
                java.util.Collections.singletonList(
                        ComponentKey.of("com.foo", "com.foo.ServiceA")));

        assertTrue(result.isWritten());
        assertEquals("", store.value);
    }

    @Test
    public void applyDelta_addWinsUnlessAlsoRemoved_removeWinsOnConflict() {
        FakeStore store = new FakeStore();
        store.value = "";
        SettingsWriter writer = new SettingsWriter(store);

        SettingsWriter.WriteResult result = writer.applyDelta(
                java.util.Arrays.asList(A, B),
                java.util.Collections.singletonList(A));

        assertTrue(result.isWritten());
        assertEquals("com.bar/com.bar.ServiceB", store.value);
        assertEquals(SettingsWriter.Status.WRITTEN, result.getStatus());
    }

    @Test
    public void isLastWrittenValue_onlyMatchesExactSuccessfulWrite() {
        FakeStore store = new FakeStore();
        store.value = "com.foo/.ServiceA";
        SettingsWriter writer = new SettingsWriter(store);

        assertTrue(writer.applyDelta(
                java.util.Collections.singletonList(B),
                java.util.Collections.<ComponentKey>emptyList()).isWritten());
        String written = writer.getLastWrittenValue();
        assertTrue(writer.isLastWrittenValue(written));
        assertFalse(writer.isLastWrittenValue("com.foo/.ServiceA"));
        assertFalse(writer.isLastWrittenValue(null));
    }
}

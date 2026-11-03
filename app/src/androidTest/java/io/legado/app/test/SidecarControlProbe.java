package io.legado.app.test;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit host control of a UUID-owned provider; the formal package is never instrumented. */
@RunWith(AndroidJUnit4.class)
public final class SidecarControlProbe {
    private Object jsonValue(Object value) throws Exception {
        if (value instanceof byte[]) return Base64.encodeToString((byte[]) value, Base64.NO_WRAP);
        if (value instanceof Bundle) {
            JSONObject result = new JSONObject();
            for (String key : ((Bundle) value).keySet()) result.put(key, jsonValue(((Bundle) value).get(key)));
            return result;
        }
        if (value instanceof ArrayList) {
            JSONArray result = new JSONArray();
            for (Object item : (ArrayList<?>) value) result.put(jsonValue(item));
            return result;
        }
        return value == null ? JSONObject.NULL : value;
    }

    @Test public void controlOwnedProvider() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Requires explicit owned-provider host controller", "yes".equals(args.getString("sidecarControl")));
        String id = args.getString("caseId");
        assertNotNull(id);
        assertEquals(id, UUID.fromString(id).toString());
        String request = args.getString("requestId");
        assertNotNull(request);
        assertEquals(request, UUID.fromString(request).toString());
        String method = args.getString("method");
        assertTrue(Arrays.asList("create", "attach", "seed", "arm", "snapshot", "release", "revoke", "dispose").contains(method));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.xiaobaitu.reader.debug", context.getPackageName());
        Bundle extras = new Bundle();
        if ("create".equals(method)) extras.putBoolean("persistable", true);
        if ("seed".equals(method)) {
            String path = args.getString("path");
            assertNotNull(path);
            assertFalse(path.isEmpty());
            assertFalse(path.startsWith("/"));
            extras.putString("path", path);
            extras.putByteArray("bytes", Base64.decode(args.getString("bytes"), Base64.DEFAULT));
        }
        if ("arm".equals(method)) {
            String mode = args.getString("mode");
            assertTrue(Arrays.asList("normal", "hold_main_create_before", "hold_main_create_after",
                    "hold_image_create_before", "hold_image_create_after", "hold_before_activation",
                    "hold_after_activation", "hold_after_backup", "hold", "hold_read",
                    "write_fail", "main_write_fail", "read_fail", "rename_fail").contains(mode));
            extras.putString("mode", mode);
        }
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://com.xiaobaitu.reader.debug.test.sidecar.control"), "owned_" + method, id, extras);
        assertNotNull(result);
        if ("create".equals(method) || "attach".equals(method)) {
            assertNotEquals(Process.myPid(), result.getInt("pid"));
            assertNotEquals(Process.myUid(), result.getInt("uid"));
        }
        JSONObject record = new JSONObject();
        record.put("id", id);
        record.put("request", request);
        record.put("method", method);
        record.put("debug_pid", Process.myPid());
        record.put("debug_uid", Process.myUid());
        record.put("result", jsonValue(result));
        File directory = new File(context.getFilesDir(), "compat-sidecar-control");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File destination = new File(directory, request + ".json");
        assertFalse(destination.exists());
        Files.write(destination.toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
    }
}

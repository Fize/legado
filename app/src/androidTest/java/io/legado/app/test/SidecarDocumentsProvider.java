package io.legado.app.test;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** SDK-only code: this provider runs under the separate test APK UID, outside instrumentation. */
public final class SidecarDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.xiaobaitu.reader.debug.test.sidecar";
    private static final String TARGET = "com.xiaobaitu.reader.debug";
    private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String[] COLUMNS = {"document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"};
    private final Map<String, Fixture> fixtures = new ConcurrentHashMap<>();
    private Handler handler;
    private static SidecarDocumentsProvider instance;

    private static final class Entry {
        final String id, parent;
        final File file;
        final boolean directory;
        volatile String name;
        Entry(String id, String parent, String name, File file, boolean directory) {
            this.id = id; this.parent = parent; this.name = name; this.file = file; this.directory = directory;
        }
    }
    private static final class Fixture {
        final String id;
        final File root;
        final Map<String, Entry> entries = new ConcurrentHashMap<>();
        final AtomicInteger descriptors = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger renames = new AtomicInteger();
        final AtomicInteger activeRenames = new AtomicInteger();
        final AtomicInteger activeCreates = new AtomicInteger();
        volatile String mode = "normal", enteredName = "", operation = "";
        volatile CountDownLatch release = new CountDownLatch(0);
        Fixture(String id, File root) throws IOException {
            this.id = id; this.root = root;
            if (root.exists() || !root.mkdirs()) throw new IOException("Fixture directory already exists");
            entries.put(id + ":root", new Entry(id + ":root", null, "Owned-" + id, root, true));
        }
    }

    @Override public boolean onCreate() {
        instance = this;
        HandlerThread thread = new HandlerThread("owned-sidecar-fuse"); thread.start(); handler = new Handler(thread.getLooper());
        return true;
    }

    private void checkCaller() {
        String[] names = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (names == null || !Arrays.asList(names).contains(TARGET)) throw new SecurityException("Only the owned debug target may control fixtures");
    }
    private Fixture fixture(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid fixture ID");
        Fixture value = fixtures.get(id);
        if (value == null) throw new IllegalStateException("Unknown fixture");
        return value;
    }
    private Fixture owner(String documentId) { return fixture(documentId.substring(0, documentId.indexOf(':'))); }
    private Entry entry(String documentId) throws FileNotFoundException {
        Entry value = owner(documentId).entries.get(documentId);
        if (value == null) throw new FileNotFoundException("Missing owned document");
        return value;
    }
    private static void validName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid document name");
    }
    private Entry child(Fixture f, String parent, String name) {
        for (Entry e : f.entries.values()) if (parent.equals(e.parent) && name.equals(e.name)) return e;
        return null;
    }
    private Entry create(Fixture f, String parent, String name, boolean directory) throws IOException {
        validName(name);
        if (!entry(parent).directory || child(f, parent, name) != null) throw new IOException("Invalid or occupied parent");
        String id = f.id + ":" + UUID.randomUUID();
        File file = new File(f.root, id.substring(id.indexOf(':') + 1));
        if (!(directory ? file.mkdir() : file.createNewFile())) throw new IOException("Cannot create owned document");
        Entry e = new Entry(id, parent, name, file, directory); f.entries.put(id, e); return e;
    }
    private String path(Fixture f, Entry e) {
        if (e.parent == null) return "";
        String parent = path(f, f.entries.get(e.parent));
        return parent.isEmpty() ? e.name : parent + "/" + e.name;
    }
    private void remove(Fixture f, Entry e) throws IOException {
        for (Entry c : new ArrayList<>(f.entries.values())) if (e.id.equals(c.parent)) remove(f, c);
        if (!e.file.delete()) throw new IOException("Cannot delete owned document");
        f.entries.remove(e.id);
    }

    static Bundle control(String method, String arg, Bundle extras) {
        if (instance == null) throw new IllegalStateException("Owned document provider is not initialized");
        return instance.ownedCall(method, arg, extras);
    }
    private Bundle ownedCall(String method, String arg, Bundle extras) {
        if (!method.startsWith("owned_")) throw new IllegalArgumentException("Unknown control method");
        checkCaller(); Bundle result = new Bundle();
        try {
            if (method.equals("owned_create")) {
                if (!UUID.fromString(arg).toString().equals(arg) || fixtures.containsKey(arg)) throw new IllegalArgumentException("Existing fixture");
                File base = new File(getContext().getCacheDir(), "saf-sidecar-fixtures");
                if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Cannot create fixture parent");
                Fixture f = new Fixture(arg, new File(base, arg)); fixtures.put(arg, f);
                Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, arg + ":root");
                int flags = GRANTS | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
                if (extras != null && extras.getBoolean("persistable")) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
                getContext().grantUriPermission(TARGET, tree, flags);
                result.putString("tree", tree.toString());
                result.putInt("pid", android.os.Process.myPid()); result.putInt("uid", android.os.Process.myUid());
                return result;
            }
            Fixture f = fixture(arg);
            switch (method) {
                case "owned_attach":
                    result.putString("tree", DocumentsContract.buildTreeDocumentUri(AUTHORITY, arg + ":root").toString());
                    result.putInt("pid", android.os.Process.myPid()); result.putInt("uid", android.os.Process.myUid()); break;
                case "owned_seed": {
                    String[] parts = extras.getString("path").split("/", -1); String parent = f.id + ":root";
                    for (int i = 0; i < parts.length; i++) {
                        validName(parts[i]); boolean directory = i + 1 < parts.length;
                        Entry e = child(f, parent, parts[i]);
                        if (e == null) e = create(f, parent, parts[i], directory);
                        if (e.directory != directory) throw new IOException("Wrong fixture kind");
                        if (!directory) Files.write(e.file.toPath(), extras.getByteArray("bytes"));
                        parent = e.id;
                    }
                    break;
                }
                case "owned_duplicate": {
                    Entry original = entry(f.id + ":root");
                    for (String part : extras.getString("path").split("/", -1)) {
                        validName(part); original = child(f, original.id, part);
                        if (original == null) throw new IOException("Missing duplicate source");
                    }
                    if (original.directory) throw new IOException("Only owned files may be duplicated");
                    String token = UUID.randomUUID().toString(); File copy = new File(f.root, token);
                    Files.copy(original.file.toPath(), copy.toPath());
                    Entry duplicate = new Entry(f.id + ":" + token, original.parent, original.name, copy, false);
                    f.entries.put(duplicate.id, duplicate); break;
                }
                case "owned_arm":
                    if (f.descriptors.get() != 0 || f.activeRenames.get() != 0 || f.activeCreates.get() != 0)
                        throw new IllegalStateException("Provider operations are still active");
                    f.mode = extras.getString("mode"); f.enteredName = ""; f.operation = "";
                    f.release = new CountDownLatch(f.mode.startsWith("hold") ? 1 : 0); break;
                case "owned_release": f.release.countDown(); break;
                case "owned_allow_new_requests":
                    if (f.activeCreates.get() != 1 || !f.mode.endsWith("create_before"))
                        throw new IllegalStateException("Expected one older pending creation");
                    f.mode = "normal"; break; // Keep the older request's release latch closed.
                case "owned_revoke":
                    getContext().revokeUriPermission(DocumentsContract.buildTreeDocumentUri(AUTHORITY, arg + ":root"), GRANTS); break;
                case "owned_grant":
                    getContext().grantUriPermission(TARGET, DocumentsContract.buildTreeDocumentUri(AUTHORITY, arg + ":root"),
                            GRANTS | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); break;
                case "owned_snapshot": {
                    Bundle files = new Bundle(); ArrayList<String> paths = new ArrayList<>();
                    for (Entry e : f.entries.values()) {
                        String path = path(f, e); paths.add(path);
                        if (!e.directory) files.putByteArray(path, Files.readAllBytes(e.file.toPath()));
                    }
                    result.putBundle("files", files); result.putStringArrayList("paths", paths);
                    result.putString("entered", f.enteredName); result.putInt("descriptors", f.descriptors.get());
                    result.putInt("activeRenames", f.activeRenames.get());
                    result.putInt("activeCreates", f.activeCreates.get());
                    result.putInt("pid", android.os.Process.myPid());
                    result.putInt("writes", f.writes.get()); result.putInt("reads", f.reads.get());
                    result.putString("operation", f.operation); result.putInt("renames", f.renames.get()); break;
                }
                case "owned_dispose":
                    f.release.countDown();
                    if (f.descriptors.get() != 0 || f.activeRenames.get() != 0 || f.activeCreates.get() != 0)
                        throw new IllegalStateException("Fixture still owns active operations");
                    getContext().revokeUriPermission(DocumentsContract.buildTreeDocumentUri(AUTHORITY, arg + ":root"), GRANTS);
                    remove(f, entry(f.id + ":root")); fixtures.remove(arg); break;
                default: throw new IllegalArgumentException("Unknown owned command");
            }
            return result;
        } catch (IOException e) { throw new IllegalStateException("Owned provider I/O failed", e); }
    }

    private MatrixCursor cursor(String[] projection) { return new MatrixCursor(projection == null ? COLUMNS : projection); }
    private void row(MatrixCursor cursor, Entry e) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case "document_id": row.add(e.id); break;
                case "_display_name": row.add(e.name); break;
                case "mime_type": row.add(e.directory ? DIR : "application/octet-stream"); break;
                case "flags": row.add(e.directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE |
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME); break;
                case "_size": row.add(e.directory ? 0 : e.file.length()); break;
                case "last_modified": row.add(e.file.lastModified()); break;
                default: row.add(null);
            }
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection == null ? new String[]{"root_id", "document_id", "title", "flags"} : projection);
        for (Fixture f : fixtures.values()) {
            MatrixCursor.RowBuilder row = result.newRow();
            for (String c : result.getColumnNames()) {
                if (c.equals("root_id")) row.add(f.id);
                else if (c.equals("document_id")) row.add(f.id + ":root");
                else if (c.equals("title")) row.add("Owned SAF fixture");
                else if (c.equals("flags")) row.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE |
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
                else row.add(null);
            }
        }
        return result;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = cursor(projection); row(result, entry(documentId)); return result;
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        entry(parentId); MatrixCursor result = cursor(projection);
        for (Entry e : owner(parentId).entries.values()) if (parentId.equals(e.parent)) row(result, e);
        return result;
    }
    @Override public boolean isChildDocument(String parentId, String documentId) {
        try {
            Entry current = entry(documentId);
            while (current.parent != null) { if (parentId.equals(current.parent)) return true; current = entry(current.parent); }
            return false;
        } catch (FileNotFoundException | IllegalArgumentException | IllegalStateException e) { return false; }
    }
    @Override public String createDocument(String parent, String mimeType, String name) throws FileNotFoundException {
        Fixture f = owner(parent);
        boolean main = !DIR.equals(mimeType) && name.contains(".pending.") && name.endsWith(".txt") && f.mode.startsWith("hold_main_create_");
        boolean image = !DIR.equals(mimeType) && name.contains(".pending.") && name.endsWith(".jpg") && f.mode.startsWith("hold_image_create_");
        boolean gated = main || image;
        if (gated) f.activeCreates.incrementAndGet();
        try {
            if (gated && f.mode.endsWith("before")) renameGate(f, "create_before", name);
            Entry created = create(f, parent, name, DIR.equals(mimeType));
            if (gated && f.mode.endsWith("after")) renameGate(f, "create_after", name);
            return created.id;
        }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        finally { if (gated) f.activeCreates.decrementAndGet(); }
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        try { remove(owner(id), entry(id)); } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        validName(name); Entry e = entry(id); Fixture f = owner(id);
        if (f.mode.equals("rename_fail") && e.name.contains(".pending.") && e.name.endsWith(".jpg")) return null;
        if (child(f, e.parent, name) != null) throw new FileNotFoundException("Name occupied");
        boolean image = e.name.endsWith(".jpg");
        boolean activation = image && e.name.contains(".pending.") && !name.contains(".pending.");
        boolean before = activation && f.mode.equals("hold_before_activation");
        boolean after = activation && f.mode.equals("hold_after_activation") ||
                image && name.contains(".backup.") && f.mode.equals("hold_after_backup");
        if (before || after) f.activeRenames.incrementAndGet();
        try {
            if (before) renameGate(f, "rename_before", e.name);
            e.name = name; f.renames.incrementAndGet(); // Deliberately preserve the URI across rename.
            if (after) renameGate(f, "rename_after", name);
            return id;
        } finally { if (before || after) f.activeRenames.decrementAndGet(); }
    }
    private void renameGate(Fixture f, String operation, String name) throws FileNotFoundException {
        f.operation = operation; f.enteredName = name;
        try {
            if (!f.release.await(20, TimeUnit.SECONDS)) throw new FileNotFoundException("Owned rename gate timed out");
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Owned rename interrupted"); }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        Entry e = entry(id); Fixture f = owner(id);
        if (e.directory) throw new FileNotFoundException("Cannot open directory");
        boolean writing = mode.contains("w");
        boolean proxyRead = !writing && (f.mode.equals("hold_read") || f.mode.equals("read_fail"));
        boolean failMainWrite = writing && f.mode.equals("main_write_fail") && e.name.contains(".pending.") && e.name.endsWith(".txt");
        if ((!e.name.endsWith(".jpg") && !failMainWrite) || (!writing && !proxyRead)) return ParcelFileDescriptor.open(e.file, ParcelFileDescriptor.parseMode(mode));
        try {
            RandomAccessFile file = new RandomAccessFile(e.file, writing ? "rw" : "r");
            if (writing) file.setLength(0);
            f.descriptors.incrementAndGet();
            try {
                return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(
                    writing ? ParcelFileDescriptor.MODE_WRITE_ONLY : ParcelFileDescriptor.MODE_READ_ONLY, new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() { return e.file.length(); }
                        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                            f.reads.incrementAndGet(); f.enteredName = e.name; f.operation = "read";
                            try {
                                if (!f.release.await(20, TimeUnit.SECONDS)) throw new IOException("Owned provider read gate timed out");
                                if (f.mode.equals("read_fail")) throw new ErrnoException("owned read EIO", OsConstants.EIO);
                                file.seek(offset); return Math.max(0, file.read(data, 0, size));
                            } catch (IOException | InterruptedException failure) { throw new ErrnoException("owned proxy read", OsConstants.EIO); }
                        }
                        @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                            f.writes.incrementAndGet();
                            if (f.mode.equals("hold")) { f.enteredName = e.name; f.operation = "write"; }
                            try {
                                if (f.mode.equals("hold") && !f.release.await(20, TimeUnit.SECONDS)) throw new IOException("Owned provider gate timed out");
                                if (f.mode.equals("write_fail") || failMainWrite) throw new ErrnoException("owned ENOSPC", OsConstants.ENOSPC);
                                file.seek(offset); file.write(data, 0, size); return size;
                            } catch (IOException | InterruptedException failure) { throw new ErrnoException("owned proxy write", OsConstants.EIO); }
                        }
                        @Override public void onFsync() throws ErrnoException {
                            try { file.getFD().sync(); } catch (IOException failure) { throw new ErrnoException("owned proxy sync", OsConstants.EIO); }
                        }
                        @Override public void onRelease() {
                            try { file.close(); } catch (IOException ignored) { }
                            finally { f.descriptors.decrementAndGet(); }
                        }
                    }, handler);
            } catch (IOException | RuntimeException failure) { file.close(); f.descriptors.decrementAndGet(); throw failure; }
        } catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
    }
}

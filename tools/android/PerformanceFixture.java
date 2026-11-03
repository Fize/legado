import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shell-only bridge for JSON values that Android's content CLI cannot represent. */
public final class PerformanceFixture {
    private static final String PACKAGE = "com.xiaobaitu.reader.release";
    private static final String BASE = "/data/user/0/" + PACKAGE + "/files/books/";
    public static void main(String[] args) throws Exception {
        if ((args.length != 1 && !(args.length == 2 && args[1].equals("cover"))) || !args[0].matches("/data/local/tmp/legado-performance-[a-f0-9]{32}\\.json"))
            throw new IllegalArgumentException("Owned JSON path required");
        String input = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");
        boolean coverMode = args.length == 2;
        JSONArray requested = coverMode ? null : new JSONArray(input);
        Class<?> queryClass = Class.forName("com.android.commands.content.Content$QueryCommand");
        java.lang.reflect.Constructor<?> queryConstructor = queryClass.getDeclaredConstructor(Uri.class, int.class, String[].class, Bundle.class);
        queryConstructor.setAccessible(true);
        Object query = queryConstructor.newInstance(Uri.parse("content://" + PACKAGE + ".readerProvider/books/query"), 0, null, Bundle.EMPTY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try { System.setOut(new PrintStream(output)); execute(query); }
        finally { System.setOut(originalOut); }
        String line = output.toString("UTF-8").trim();
        if (!line.startsWith("Row: 0 result=")) throw new IllegalStateException("Missing bookshelf result");
        JSONObject result = new JSONObject(line.substring("Row: 0 result=".length()));
        JSONArray current = result.getJSONArray("data");
        if (!result.getBoolean("isSuccess") || current.length() != 201 || (!coverMode && requested.length() != 201))
            throw new IllegalStateException("Complete owned fixture required");
        Map<String, JSONObject> originals = new HashMap<>();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 200; i++) expected.add(BASE + String.format(java.util.Locale.ROOT, "CompatPerfV1Book%03d.txt", i));
        expected.add(BASE + "CompatPerfV1Reader.txt");
        for (int i = 0; i < current.length(); i++) {
            JSONObject book = current.getJSONObject(i);
            String url = book.getString("bookUrl");
            if (!expected.contains(url) || originals.put(url, book) != null)
                throw new IllegalStateException("Foreign or duplicate book");
        }
        if (coverMode) {
            JSONObject change = new JSONObject(input);
            JSONObject book = originals.get(BASE + "CompatPerfV1Book000.txt");
            Object expectedCover = change.get("expected");
            Object currentCover = book.opt("customCoverUrl");
            if (currentCover == null) currentCover = JSONObject.NULL;
            if (!currentCover.equals(expectedCover)) throw new IllegalStateException("Owned cover changed unexpectedly");
            Object next = change.get("next");
            if (!JSONObject.NULL.equals(next) && !next.toString().matches("file:///sdcard/Android/data/" + PACKAGE.replace(".", "\\.") + "/files/compat-performance-v1/(square4096|square8192|tall8192)\\.png"))
                throw new IllegalArgumentException("Only owned large-image paths are accepted");
            if (JSONObject.NULL.equals(next)) book.remove("customCoverUrl"); else book.put("customCoverUrl", next);
            insertBook(book);
            System.out.println("COMPAT_LARGE_IMAGE_COVER:1");
            System.exit(0);
        }
        Set<String> submitted = new HashSet<>();
        for (int i = 0; i < requested.length(); i++) {
            JSONObject book = requested.getJSONObject(i);
            String url = book.getString("bookUrl");
            JSONObject original = originals.get(url);
            if (original == null || !submitted.add(url) || !book.getString("name").equals(original.getString("name"))
                    || !book.getString("author").equals(original.getString("author"))
                    || book.getInt("durChapterIndex") != 0 || book.getInt("durChapterPos") != 0
                    || book.getLong("durChapterTime") != 0 || book.getBoolean("canUpdate"))
                throw new IllegalStateException("Unsafe fixture reset");
        }
        for (int i = 0; i < requested.length(); i++) insertBook(requested.getJSONObject(i));
        System.out.println("COMPAT_PERFORMANCE_RESET:201");
        System.exit(0);
    }
    private static void insertBook(JSONObject book) throws Exception {
        Class<?> type = Class.forName("com.android.commands.content.Content$InsertCommand");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(Uri.class, int.class, ContentValues.class, Bundle.class);
        constructor.setAccessible(true);
        ContentValues values = new ContentValues(); values.put("json", book.toString());
        execute(constructor.newInstance(Uri.parse("content://" + PACKAGE + ".readerProvider/book/insert"), 0, values, Bundle.EMPTY));
    }
    // Reuse the system command's real shell attribution and exported-provider checks.
    private static void execute(Object command) throws Exception {
        java.lang.reflect.Method execute = command.getClass().getSuperclass().getDeclaredMethod("execute");
        execute.setAccessible(true);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try { System.setErr(new PrintStream(errors)); execute.invoke(command); }
        finally { System.setErr(originalErr); }
        if (errors.size() != 0) throw new IllegalStateException(errors.toString("UTF-8"));
    }

}

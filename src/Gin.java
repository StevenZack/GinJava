import static android.content.ContentValues.TAG;

import android.util.Log;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.util.*;

public class Gin {

    private volatile boolean isRunning = false;
    private ServerSocket serverSocket;
    private final Map<String, Map<String, Handler>> handlerMap = new HashMap<>();
    private final Map<String, Map<String, Handler>> multiPathHandlerMap = new HashMap<>();

    private String cacheDir;
    public Gin(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public void getMultiple(String path, Handler handler) {
        handleMultiFunc(Methods.get, path, handler);
    }

    public void handleMultiFunc(String method, String path, Handler handler) {
        Map<String, Handler> v = multiPathHandlerMap.get(path);
        if (v == null) {
            v = new HashMap<>();
            multiPathHandlerMap.put(path, v);
        }
        v.put(method, handler);
    }

    public void handleFunc(String method, String path, Handler handler) {
        Map<String, Handler> v = handlerMap.get(path);
        if (v == null) {
            v = new HashMap<>();
            handlerMap.put(path, v);
        }
        v.put(method, handler);
    }

    public void get(String path, Handler handler) {
        handleFunc(Methods.get, path, handler);
    }

    public void post(String path, Handler handler) {
        handleFunc(Methods.post, path, handler);
    }

    public void put(String path, Handler handler) {
        handleFunc(Methods.put, path, handler);
    }

    public void listen(int port) {
        new Thread(() -> {
            try {
                run(port);
            } catch (
                IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void run(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        isRunning = true;
        while (isRunning) {
            Socket socket = serverSocket.accept();
            new Thread(() -> {
                serveHTTP(socket);
            }).start();
        }
    }

    public void stop() throws IOException {
        isRunning = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    private boolean containsMultiplePath(String path) {
        for (Map.Entry<String, Map<String, Handler>> m : multiPathHandlerMap.entrySet()) {
            if (path.startsWith(m.getKey())) {
                return true;
            }
        }
        return false;
    }

    private void serveHTTP(Socket socket) {
        Context context = null;
        try {
            context = new Context(socket,cacheDir);
            String path = context.request.path();
            if (handlerMap.containsKey(path)) {
                Map<String, Handler> m = handlerMap.get(path);
                if (m != null) {
                    Handler h = m.get(context.request.method);
                    if (h != null) {
                        h.handle(context);
                    } else {
                        context.string(Status.methodNotAllowed, Status.getMessage(Status.methodNotAllowed));
                    }
                } else {
                    context.notFound();
                }
            } else if (containsMultiplePath(path)) {
                for (Map.Entry<String, Map<String, Handler>> entry : multiPathHandlerMap.entrySet()) {
                    if (!path.startsWith(entry.getKey())) {
                        continue;
                    }
                    // found it
                    Map<String, Handler> m = entry.getValue();
                    if (!m.containsKey(context.request.method)) {
                        context.string(Status.methodNotAllowed, Status.getMessage(Status.methodNotAllowed));
                        break;
                    }

                    // multiFunc
                    Handler handler = m.get(context.request.method);
                    if (handler != null) {
                        handler.handle(context);
                    }
                    break;
                }
            } else {
                context.notFound();
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (context != null) {
                try {
                    context.response.internalServerError(e.getMessage());
                } catch (IOException ex) {
                    e.printStackTrace();
                }
            }
        }

        // close connection
        if (context != null) {
            try {
                context.response.flushData();
                context.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    // child classes
    public static class Context {
        public final Request request;
        public final Response response;

        private final Socket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private boolean bodyReadStarted = false;
        private long bodyReadN = 0; // bytes left to read
        private String cacheDir;
        public Context(Socket socket,String cacheDir) throws Exception {
            this.cacheDir = cacheDir;
            this.socket = socket;
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
            this.request = Request.readContext(this);
            this.response = new Response(this);
        }

        public void close() throws IOException {
            inputStream.close();
            outputStream.flush();
            outputStream.close();
            socket.close();
        }

        public String readStringUntil(String sep, boolean excludeSep) throws IOException {
            byte[] sepBytes = sep.getBytes("UTF-8");
            ByteArrayOutputStream builder = new ByteArrayOutputStream();

            readBytesUntil(builder, sep.getBytes("UTF-8"));
            byte[] out = builder.toByteArray();

            if (excludeSep) {
                if (StrX.bytesEndsWith(out, sepBytes)) {
                    out = Arrays.copyOf(out, out.length - sepBytes.length);
                }
            }
            return new String(out, "UTF-8");
        }

        private void readBytesUntil(OutputStream outputStream, byte[] sep) throws IOException {
            byte[] window = new byte[sep.length];
            int n = 0;
            while (!StrX.bytesEquals(window, sep)) {
                int vi = inputStream.read();
                if (vi == -1) {
                    break;
                }
                byte v = (byte) vi;
                outputStream.write(v);

                if (bodyReadStarted && bodyReadN > -1) {
                    bodyReadN--;
                    if (bodyReadN <= 0) {
                        break;
                    }
                }

                if (n < window.length) {
                    window[n] = v;
                    n++;
                    continue;
                }
                for (int i = 0; i < window.length; i++) {
                    if (i < window.length - 1) {
                        window[i] = window[i + 1];
                        continue;
                    }
                    window[i] = v;
                }
            }
        }


        public String requestURI() {
            return request.requestURI;
        }

        public void serveFile(String path) throws IOException {
            response.serveFile(path);
        }

        public void string(int code, String s) throws IOException {
            response.string(code, s);
        }

        public void html(int code, String s) throws IOException {
            response.html(code, s);
        }

        public void html(int code, DSL.Element element) throws IOException {
            html(code, element.marshal());
        }

        public void notFound() throws IOException {
            response.notFound();
        }

        public void forbidden() throws IOException {
            response.forbidden();
        }

        public void badRequest(String s) throws IOException {
            response.badRequest(s);
        }

        public void header(String key, String value) {
            response.headers.put(key, value);
        }

        public void readBodyToFile(String dst) throws IOException {
            new File(StrX.subBeforeLast(dst, "/", "/")).mkdirs();
            File file = new File(dst);
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();

            FileOutputStream fileOutputStream = new FileOutputStream(file);
            FileChannel fileChannel = fileOutputStream.getChannel();
            ReadableByteChannel readableByteChannel = Channels.newChannel(inputStream);
            fileChannel.transferFrom(readableByteChannel, 0, bodyReadN);
            fileOutputStream.close();
        }

        public String bodyAsText() throws IOException {
            return request.bodyAsText();
        }
    }

    public static class Request {
        public String method;
        public String requestURI;
        public String proto;
        public Map<String, String> searchMap;

        public Map<String, String> headers = new HashMap<>();

        private Context context;

        public Request() {

        }

        public String query(String key) {
            if (searchMap == null) {
                String search = StrX.subAfter(requestURI, "?", "");
                searchMap = new HashMap<>();
                if (!search.isEmpty()) {
                    String[] ss = search.split("&");
                    for (String s : ss) {
                        String[] kv = s.split("=");
                        if (kv.length != 2) {
                            continue;
                        }
                        try {
                            searchMap.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            String s = searchMap.get(key);
            if (s == null) {
                s = "";
            }
            return s;
        }

        public long getContentLength() {
            String s = headers.get(Headers.contentLength);
            if (s == null || s.isEmpty()) {
                return -1;
            }
            try {
                return Long.parseLong(s);
            } catch (Exception e) {
                e.printStackTrace();
                return -1;
            }
        }

        public String path() {
            return StrX.subBeforeLast(requestURI, "?", requestURI);
        }

        public static Request readContext(Context context) throws Exception {
            Request req = new Request();
            req.method = context.readStringUntil(" ", true);
            if (!Methods.validate(req.method)) {
                throw new Exception("invalid http method:" + req.method);
            }
            req.requestURI = context.readStringUntil(" ", true);
            req.proto = context.readStringUntil("\r\n", true);

            String h = context.readStringUntil("\r\n\r\n", true);
            String[] hs = h.split("\r\n");
            for (int i = 0; i < hs.length; i++) {
                String[] hss = hs[i].split(": ");
                String value = "";
                if (hss.length > 1) {
                    value = hss[1];
                }
                req.headers.put(hss[0], value);
            }
            context.bodyReadStarted = true;
            context.bodyReadN = req.getContentLength();
            //body
            req.context = context;
            return req;
        }



        public String bodyAsText() throws IOException {
            return context.readStringUntil("\r\n\r\n", true);
        }

        public void bodyCopyToFile(String dst) throws IOException {
            context.readBodyToFile(dst);
        }

        public static class MultipartFormField {
            public String filename;
            public long length;
            public String name;
            public String value;
            public File file;
        }

        public static class MultipartFormBody{
            private File file;
            private long nLeftToRead;
            private BufferedReader bufferedReader;
            public MultipartFormBody(String filePath) throws FileNotFoundException {
                file = new File(filePath);
                nLeftToRead = file.length();
                bufferedReader = new BufferedReader(new FileReader(filePath));
            }

            public String readStringUntil(String sep,boolean excludeSep) throws IOException {
                byte[] sepBytes = sep.getBytes("UTF-8");
                ByteArrayOutputStream builder = new ByteArrayOutputStream();
                readBytesUntil(builder, sep.getBytes("UTF-8"));
                byte[] out = builder.toByteArray();

                if (excludeSep) {
                    if (StrX.bytesEndsWith(out, sepBytes)) {
                        out = Arrays.copyOf(out, out.length - sepBytes.length);
                    }
                }
                if (out.length == 0) {
                    return "";
                }
                return new String(out, "UTF-8");
            }

            public void readBytesUntil(BufferedWriter outputStream,byte[] sep,boolean excludeSep) throws IOException {
                byte[] window = new byte[sep.length];
                int n = 0;
                while (!StrX.bytesEquals(window, sep)) {
                    int vi = bufferedReader.read();
                    if (vi == -1) {
                        break;
                    }
                    byte v = (byte) vi;
                    if (!excludeSep) {
                        outputStream.write(v);
                    }

                    nLeftToRead--;
                    if (nLeftToRead <= 0) {
                        break;
                    }

                    if (n < window.length) {
                        window[n] = v;
                        n++;
                        continue;
                    }

                    if (excludeSep) {
                        outputStream.write(window[0]);
                    }
                    for (int i = 0; i < window.length; i++) {
                        if (i < window.length - 1) {
                            window[i] = window[i + 1];
                            continue;
                        }
                        window[i] = v;
                    }
                }
            }

            public void readBytesUntil(OutputStream outputStream,byte[] sep) throws IOException {
                byte[] window = new byte[sep.length];
                int n = 0;
                while (!StrX.bytesEquals(window, sep)) {
                    int vi = bufferedReader.read();
                    if (vi == -1) {
                        break;
                    }
                    byte v = (byte) vi;
                    outputStream.write(v);

                    nLeftToRead--;
                    if (nLeftToRead <= 0) {
                        break;
                    }

                    if (n < window.length) {
                        window[n] = v;
                        n++;
                        continue;
                    }

                    for (int i = 0; i < window.length; i++) {
                        if (i < window.length - 1) {
                            window[i] = window[i + 1];
                            continue;
                        }
                        window[i] = v;
                    }
                }
            }

            public void close() throws IOException {
                bufferedReader.close();
            }

            public void delete(){
                file.delete();
            }
        }

        /**
         ------WebKitFormBoundary1FcV3vleeUX7Akxe
         Content-Disposition: form-data; name="file"; filename="不对.txt"
         Content-Type: text/plain

         qwe
         sdwefr
         ===

         ------WebKitFormBoundary1FcV3vleeUX7Akxe
         Content-Disposition: form-data; name="file"; filename="你好.txt"
         Content-Type: text/plain

         asd
         qwe
         ------WebKitFormBoundary1FcV3vleeUX7Akxe
         Content-Disposition: form-data; name="username"

         健康减肥
         ------WebKitFormBoundary1FcV3vleeUX7Akxe--
         * @return
         * @throws Exception
         */
        public List<MultipartFormField> parseMultipartForm() throws Exception {
            // boundary: multipart/form-data; boundary=----WebKitFormBoundaryq0y6gUYIaRVQsaSa
            String s = headers.get("Content-Type");
            if (s == null || !s.startsWith("multipart/form-data")) {
                throw new Exception("invalid content-type for multipart/form-data: " + s);
            }
            Log.d(TAG, "parseMultipartForm: ");
            String boundary = StrX.subAfter(s, "boundary=", "");
            if (boundary == null || boundary.isEmpty()) {
                throw new Exception("invalid content-type for multipart/form-data: " + s);
            }

            long l = getContentLength();
            if (l == 0) {
                throw new Exception("empty body, content length is 0");
            }

            boundary = "--" + boundary;

            String bodyCacheFile = FileX.joinPath(context.cacheDir, boundary);
            FileX.truncateFile(bodyCacheFile);
            FileX.readInputStreamToFile(context.inputStream,l,bodyCacheFile);

            MultipartFormBody reader = new MultipartFormBody(bodyCacheFile);
            reader.readStringUntil(boundary, true);
            List<MultipartFormField> list = new ArrayList<>();
            while (true) {
                String prefix=reader.readStringUntil("\r\n", true);
                if (prefix != null && prefix.equals("--")) {
                    break;
                }

                MultipartFormField field = new MultipartFormField();
                //headers
                String line;
                while (!(line = reader.readStringUntil("\r\n",true)).isEmpty()) {
                    if (line.startsWith("Content-Disposition")) {
                        line = StrX.subAfter(line, "; ", "");
                        String[] ss = line.split("; ");
                        for (int i = 0; i < ss.length; i++) {
                            String[] kv = ss[i].split("=");
                            if (kv.length != 2) {
                                continue;
                            }
                            if (kv[0].equals("name")) {
                                field.name = StrX.trimBoth(kv[1], "\"");
                            } else if (kv[0].equals("filename")) {
                                field.filename = StrX.trimBoth(kv[1], "\"");
                                field.filename = URLDecoder.decode(field.filename, "UTF-8");
                            }
                        }
                        continue;
                    }
                    // content type
                }

                //body
                if (field.filename == null || field.filename.isEmpty()) {
                    field.value = reader.readStringUntil(boundary, true);
                    list.add(field);
                    continue;
                }

                String dst = FileX.joinPath(context.cacheDir, boundary + field.filename);
                File fo = new File(dst);
                if (fo.exists()) {
                    fo.delete();
                }
                fo.createNewFile();
                FileOutputStream fileOutputStream=new FileOutputStream(fo);
                BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(fileOutputStream));
                reader.readBytesUntil(bufferedWriter,("\r\n"+boundary).getBytes("UTF-8"),true);
                bufferedWriter.flush();
                bufferedWriter.close();
                fileOutputStream.close();
                field.file = fo;
                field.length = fo.length();

                list.add(field);
            }
            reader.close();
            reader.delete();

            return list;
        }

        public String getContentType() {
            return headers.get(Headers.contentType);
        }
    }

    public static class Response {
        public int statusCode = Status.ok;
        public String proto = Protocol.HTTP_1_1;

        public Map<String, String> headers = new HashMap<>();

        private ByteArrayOutputStream body;
        private FileInputStream bodyInputStream;

        private final Context context;

        public Response(Context context) {
            this.context = context;
        }

        private void flushData() throws IOException {
            context.outputStream.write(proto.getBytes("UTF-8"));
            context.outputStream.write(' ');
            context.outputStream.write(String.valueOf(statusCode).getBytes("UTF-8"));
            context.outputStream.write(' ');
            context.outputStream.write(Status.getMessage(statusCode).getBytes("UTF-8"));
            //header
            if (body == null || body.size() == 0) {
                headers.put(Headers.contentLength, "0");
            } else {
                headers.put(Headers.contentLength, String.valueOf(body.size()));
            }

            context.outputStream.write("\r\n\r\n".getBytes("UTF-8"));

            //body
            if (body != null && body.size() > 0) {
                context.outputStream.write(body.toByteArray());
            } else if (bodyInputStream != null) {
                FileChannel fi = bodyInputStream.getChannel();
                WritableByteChannel fo = Channels.newChannel(context.outputStream);
                fi.transferTo(0, fi.size(), fo);
            }

            context.outputStream.write("\r\n\r\n".getBytes("UTF-8"));
        }

        public void bytes(int code, byte[] bytes) throws IOException {
            statusCode = code;
            if (body == null) {
                body = new ByteArrayOutputStream();
            }
            body.write(bytes);
        }

        public void string(int code, String s) throws IOException {
            if (s == null) {
                s = "";
            }
            setContentType(ContentTypes.textPlain);
            bytes(code, s.getBytes("UTF-8"));
        }

        public void html(int code, String s) throws IOException {
            if (s == null) {
                s = "";
            }
            setContentType(ContentTypes.textHtml);
            bytes(code, s.getBytes("UTF-8"));
        }

        public void htmlBody(int code, String s) throws IOException {
            if (s == null) {
                s = "";
            }
            html(code, "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Information</title></head><body>" + s + "</body></html>");
        }

        public void json(int code, String s) throws IOException {
            if (s == null) {
                s = "";
            }
            setContentType(ContentTypes.applicationJson);
            bytes(code, s.getBytes("UTF-8"));
        }

        public void setContentType(String s) {
            headers.put(Headers.contentType, s);
        }

        public void setContentLength(long l) {
            headers.put(Headers.contentLength, String.valueOf(l));
        }

        public void serveFile(String path) throws IOException {
            setContentType(FileX.getMimeType(path));
            File file = new File(path);
            if (!file.exists()) {
                notFound();
                return;
            }
            setContentLength(file.length());
            bodyInputStream = new FileInputStream(file);
        }

        public void notFound() throws IOException {
            htmlBody(Status.notFound, "404 not found");
        }

        public void forbidden() throws IOException {
            htmlBody(Status.forbidden,"401 Forbidden");
        }

        public void badRequest(String err) throws IOException {
            htmlBody(Status.badRequest, "400 Bad Request: " + err);
        }

        public void internalServerError(String err) throws IOException {
            htmlBody(Status.internalServerError, "500 Internal Server Error: " + err);
        }
    }

    public static class Headers {
        public static final String contentLength = "Content-Length";
        public static final String contentType = "Content-Type";
    }

    public static class ContentTypes {
        public static final String textPlain = "text/plain";
        public static final String applicationJson = "application/json";
        public static final String textHtml = "text/html";
        public static final String textJavaScript = "text/javascript";
        public static final String applicationFormUrlEncoded = "application/x-www-form-urlencoded";
    }

    public static class Status {
        public static final int ok = 200;
        public static final int badRequest = 400;
        public static final int unauthorized = 401;
        public static final int forbidden = 403;
        public static final int notFound = 404;
        public static final int methodNotAllowed = 405;
        public static final int internalServerError = 500;

        public static String getMessage(int code) {
            switch (code) {
                case ok:
                    return "OK";
                case notFound:
                    return "Not Found";
                case methodNotAllowed:
                    return "Method Not Allowed";
                case internalServerError:
                    return "Internal Server Error";
                case badRequest:
                    return "Bad Request";
                case forbidden:
                    return "Forbidden";
                case unauthorized:
                    return "Unauthorized";
            }
            return "Unknown status";
        }
    }

    public static class Protocol {
        public static final String HTTP_1_1 = "HTTP/1.1";

    }

    public static class Methods {
        public static final String get = "GET";
        public static final String post = "POST";
        public static final String put = "PUT";
        public static final String patch = "PATCH";
        public static final String head = "HEAD";
        public static final String delete = "DELETE";
        public static final String connect = "CONNECT";
        public static final String options = "OPTIONS";
        public static final String trace = "TRACE";

        public static boolean validate(String method) {
            switch (method) {
                case get:
                case post:
                case put:
                case patch:
                case head:
                case delete:
                case connect:
                case options:
                case trace:
                    return true;
            }
            return false;
        }
    }

    public interface Handler {
        void handle(Context context) throws Exception;
    }

    public static class StrX {
        public static String createQuery(Map<String, String> m) throws UnsupportedEncodingException {
            StringBuilder s = new StringBuilder();
            for (Map.Entry<String, String> entry : m.entrySet()) {
                s.append("&").append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            if (s.length() == 0) {
                return "";
            }
            return s.substring(1);
        }

        public static boolean bytesEquals(byte[] a, byte[] b) {
            if (a == null) {
                return b == null || b.length == 0;
            }
            if (b == null) {
                return a.length == 0;
            }

            if (a.length != b.length) {
                return false;
            }

            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        public static boolean bytesEndsWith(byte[] a, byte[] b) {
            if (a == null || a.length == 0 || b == null || b.length == 0 || a.length < b.length) {
                return false;
            }
            for (int i = 0; i < b.length; i++) {
                if (a[a.length - b.length + i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        public static String subAfter(String s, String sep, String def) {
            assert s != null;
            assert sep != null;

            for (int i = 0; i < s.length() - sep.length(); i++) {
                if (s.startsWith(sep, i)) {
                    return s.substring(i + sep.length());
                }
            }
            return def;
        }

        public static String subAfterLast(String s, String sep, String def) {
            assert s != null;
            assert sep != null;
            for (int i = s.length() - sep.length(); i > -1; i--) {
                if (s.startsWith(sep, i)) {
                    return s.substring(i + sep.length());
                }
            }
            return def;
        }

        public static String ellipse(String s, int len) {
            if (s == null) {
                return "";
            }
            if (s.length() <= len) {
                return s;
            }
            return s.substring(0, len) + "..";
        }

        public static String subBefore(String s, String sep, String def) {
            assert s != null;
            assert sep != null;
            for (int i = 0; i < s.length() - sep.length(); i++) {
                if (s.startsWith(sep, i)) {
                    return s.substring(0, i);
                }
            }
            return def;
        }

        public static String subBeforeLast(String s, String sep, String def) {
            assert s != null;
            assert sep != null;
            for (int i = s.length() - sep.length(); i > -1; i--) {
                if (s.startsWith(sep, i)) {
                    return s.substring(0, i);
                }
            }
            return def;
        }

        public static String trimStartAll(String s, String trim) {
            assert s != null;
            assert trim != null;
            while (true) {
                if (s.startsWith(trim)) {
                    s = s.substring(trim.length());
                    continue;
                }
                break;
            }
            return s;
        }

        public static String trimEndAll(String s, String trim) {
            assert s != null;
            assert trim != null;
            while (true) {
                if (s.endsWith(trim)) {
                    s = s.substring(0, s.length() - trim.length());
                    continue;
                }
                break;
            }
            return s;
        }

        public static String trimStart(String s, String trim) {
            assert s != null;
            assert trim != null;
            if (s.startsWith(trim)) {
                return s.substring(trim.length());
            }
            return s;
        }

        public static String trimEnd(String s, String trim) {
            assert s != null;
            assert trim != null;
            if (s.endsWith(trim)) {
                return s.substring(0, s.length() - trim.length());
            }
            return s;
        }

        public static String trimBoth(String s, String trim) {
            return trimStart(trimEnd(s, trim), trim);
        }

        public static String trimBothAll(String s, String trim) {
            return trimStart(trimEndAll(s, trim), trim);
        }

        public static String join(List<String> list, String sep) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i == list.size() - 1) {
                    builder.append(list.get(i));
                    break;
                }
                builder.append(list.get(i));
                builder.append(sep);
            }
            return builder.toString();
        }

        public static List<String> split(String s, String sep) {
            List<String> list = new ArrayList<>();
            int last = 0;
            for (int i = 0; i < s.length() - sep.length(); i++) {
                String part = s.substring(i, i + sep.length());
                if (part.equals(sep)) {
                    list.add(s.substring(last, i));
                    last = i + sep.length();
                }
            }
            list.add(s.substring(last));
            return list;
        }

        public static float parseVersion(String version) throws Exception {
            String[] ss = version.split("\\.");
            float out = 0;
            for (int i = 0; i < ss.length; i++) {
                float f = Float.parseFloat(ss[i]);
                switch (i) {
                    case 0:
                        out += f * 10000;
                        break;
                    case 1:
                        out += f * 100;
                        break;
                    case 2:
                        out += f;
                        break;
                    case 3:
                        out += f * 0.01;
                        break;
                    case 4:
                        out += f * 0.0001;
                        break;
                    case 5:
                        out += f * 0.000001;
                        break;
                }
            }
            return out;
        }

    }

    public static class FileX {
        public static void truncateFile(String path) throws IOException {
            File file = new File(path);
            file.getParentFile().mkdirs();
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();
        }

        public static void copyFile(String dst, String src) throws IOException {
            truncateFile(dst);
            final FileInputStream inputStream = new FileInputStream(src);
            final FileOutputStream outputStream = new FileOutputStream(dst);
            final FileChannel inChannel = inputStream.getChannel();
            final FileChannel outChannel = outputStream.getChannel();
            inChannel.transferTo(0, inChannel.size(), outChannel);
            inChannel.close();
            outChannel.close();
            inputStream.close();
            outputStream.close();
        }

        public static void moveFile(String dst, String src) throws IOException {
            copyFile(dst,src);
            new File(src).delete();
        }

        public static String joinPath(String dir, String name) {
            if (dir.endsWith("/")) {
                return dir + name;
            }
            return dir + "/" + name;
        }

        public static String extension(String path) {
            String name = StrX.subAfterLast(path, "/", path);
            String ext = StrX.subAfterLast(name, ".", "");
            if (ext == null || ext.isEmpty()) {
                return "";
            }
            return "." + ext;
        }

        public static String formatSize(long size) {
            long gb = size >> 30;
            if (gb > 0) {
                double f = size * 100 >> 30;
                f = f / 100;
                return String.format("%.2fG", f);
            }
            long mb = size >> 20;
            if (mb > 0) {
                double f = size * 100 >> 20;
                f = f / 100;
                return String.format("%.2fM", f);
            }
            long kb = size >> 10;
            if (kb > 0) {
                double f = size * 100 >> 10;
                f = f / 100;
                return String.format("%.2fK", f);
            }
            return size + "B";
        }

        public static String duplicateFileNameIfNeeded(String path) {
            if (!new File(path).exists()) {
                return path;
            }
            String dir = StrX.subBeforeLast(path, "/", "");
            String filename = StrX.subAfterLast(path, "/", path);
            filename = StrX.subBeforeLast(filename, ".", filename);
            String ext = extension(path);
            for (int i = 0; true; i++) {
                String dst = joinPath(dir, filename + "-" + i + ext);
                if (!new File(dst).exists()) {
                    return dst;
                }
            }
        }

        public static String getMimeType(String s) {
            String ext = StrX.subAfterLast(s, ".", "");
            if (ext.isEmpty()) {
                return "application/octet-stream";
            }
            switch (ext) {
                case "html":
                case "css":
                case "csv":
                case "htm":
                case "xml":
                    return "text/" + ext;
                case "jpeg":
                case "png":
                case "jpg":
                case "bmp":
                case "gif":
                case "tiff":
                case "webp":
                    return "image/" + ext;
                case "aac":
                case "opus":
                case "wav":
                    return "audio/" + ext;
                case "webm":
                case "mp4":
                case "flv":
                case "amv":
                case "wmv":
                case "mov":
                case "rmvb":
                case "mtv":
                case "dat":
                case "dmv":
                    return "video/" + ext;
                case "json":
                case "pdf":
                case "php":
                case "rtf":
                case "zip":
                    return "application/" + ext;
                case "csh":
                case "sh":
                case "tar":
                    return "application/x-" + ext;
                case "otf":
                case "ttf":
                case "woff":
                case "woff2":
                    return "font/" + ext;
                case "js":
                case "mjs":
                    return "text/javascript";
                case "abw":
                    return "application/x-abiword";
                case "avi":
                    return "video/x-msvideo";
                case "arc":
                    return "application/x-freearc";
                case "azw":
                    return "application/vnd.amazon.ebook";
                case "bin":
                    return "application/octet-stream";
                case "bz":
                    return "application/x-bzip";
                case "bz2":
                    return "application/x-bzip2";
                case "gz":
                    return "application/gzip";
                case "epub":
                    return "application/vnd.ms-fontobject";
                case "doc":
                    return "application/msword";
                case "docx":
                    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "eot":
                    return "application/vnd.ms-fontobject";
                case "ico":
                    return "image/vnd.microsoft.icon";
                case "ics":
                    return "text/calendar";
                case "jar":
                    return "application/java-archive";
                case "jsonld":
                    return "application/ld+json";
                case "mid":
                    return "audio/midi";
                case "midi":
                    return "audio/x-midi";
                case "mp3":
                    return "audio/mpeg";
                case "mpeg":
                    return "video/mpeg";
                case "mpkg":
                    return "application/vnd.apple.installer+xml";
                case "odp":
                    return "application/vnd.oasis.opendocument.presentation";
                case "ods":
                    return "application/vnd.oasis.opendocument.spreadsheet";
                case "odt":
                    return "application/vnd.oasis.opendocument.text";
                case "oga":
                    return "audio/oga";
                case "ogv":
                    return "video/ogg";
                case "ogx":
                    return "application/ogg";
                case "ppt":
                    return "application/vnd.ms-powerpoint";
                case "pptx":
                    return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "rar":
                    return "application/x-rar-compressed";
                case "svg":
                    return "image/svg+xml";
                case "swf":
                    return "application/x-shockwave-flsh";
                case "tif":
                    return "image/tiff";
                case "ts":
                    return "video/mp2t";
                case "vsd":
                    return "application/vnd.visio";
                case "weba":
                    return "audio/webm";
                case "xhtml":
                    return "application/xhtml+xml";
                case "xls":
                    return "application/vnd.ms-excel";
                case "xlsx":
                    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "xul":
                    return "application/vnd.mozilla.xul+xml";
                case "3gp":
                    return "video/3gpp";
                case "3g2":
                    return "video/3gpp2";
                case "7z":
                    return "application/x-7z-compressed";
                case "apk":
                    return "application/vnd.android.package-archive";
                default:
                    return "text/plain";
            }
        }

        public static void readInputStreamToFile(InputStream inputStream, long inputLength, String dst) throws IOException {
            new File(StrX.subBeforeLast(dst, "/", "/")).mkdirs();
            File file = new File(dst);
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            FileChannel fileChannel = fileOutputStream.getChannel();
            ReadableByteChannel readableByteChannel = Channels.newChannel(inputStream);
            fileChannel.transferFrom(readableByteChannel, 0, inputLength);
            fileOutputStream.close();
        }

        public static void readInputStreamToFile(InputStream inputStream, String dst) throws IOException {
            new File(StrX.subBeforeLast(dst, "/", "/")).mkdirs();
            File file = new File(dst);
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            FileChannel fileChannel = fileOutputStream.getChannel();
            ReadableByteChannel readableByteChannel = Channels.newChannel(inputStream);

            ByteBuffer byteBuffer = ByteBuffer.allocate(4 << 10);
            while (readableByteChannel.read(byteBuffer) > 0) {
                byteBuffer.flip();
                fileChannel.write(byteBuffer);
            }

            fileOutputStream.close();
        }

    }

    public static class DSL {
        public static class Element {
            protected String tagName;
            protected Map<String, String> attributes = new LinkedHashMap<>();

            public Element(String tagName) {
                this.tagName = tagName;
            }

            protected String marshal() {
                StringBuilder builder = new StringBuilder();
                builder.append("<").append(tagName);
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    builder.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
                }
                builder.append(">");
                return builder.toString();
            }

            public Element attr(String name, String value) {
                attributes.put(name, value);
                return this;
            }

            public Element className(String s) {
                attr("class", s);
                return this;
            }

            public Element id(String s) {
                attr("id", s);
                return this;
            }

            public Element style(String s) {
                attr("style", s);
                return this;
            }

            public Element onclick(String s) {
                attr("onclick", s);
                return this;
            }

            public Element onlyIf(boolean b) {
                if (b) {
                    return this;
                }
                return null;
            }
        }

        public static class InnerText extends Element {

            public InnerText(String name) {
                super(name);
            }

            @Override
            protected String marshal() {
                return tagName;
            }
        }

        public static class Container extends Element {
            protected List<Element> children = new ArrayList<>();

            public Container(String tagName) {
                super(tagName);
            }

            @Override
            protected String marshal() {
                StringBuilder builder = new StringBuilder();
                builder.append("<").append(tagName);
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    builder.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
                }
                builder.append(">");
                for (Element element : children) {
                    if (element == null) {
                        continue;
                    }
                    builder.append(element.marshal());
                }
                builder.append("</").append(tagName).append(">");
                return builder.toString();
            }

            public Container body(Element... elements) {
                children.addAll(Arrays.asList(elements));
                return this;
            }

            public <T> Container bodyListOfIndex(List<T> list, ElementTypeIndexHandler<T> handler) {
                for (int i = 0; i < list.size(); i++) {
                    children.add(handler.handle(list.get(i), i));
                }
                return this;
            }

            public <T> Container bodyListOf(List<T> list, ElementTypeHandler<T> handler) {
                for (int i = 0; i < list.size(); i++) {
                    children.add(handler.handle(list.get(i)));
                }
                return this;
            }

            public Container setBody(Element... elements) {
                children.clear();
                children.addAll(Arrays.asList(elements));
                return this;
            }
        }

        public interface ElementTypeIndexHandler<T> {
            Element handle(T v, int pos);
        }

        public interface ElementTypeHandler<T> {
            Element handle(T v);
        }

        public static class Html extends Container {

            public Html() {
                super("html");
                attr("lang", "en");
            }

            @Override
            protected String marshal() {
                String s = super.marshal();
                return "<!DOCTYPE html>" + s;
            }
        }

        public static class Head extends Container {
            public Head() {
                super("head");
                body(
                    new Element("meta").attr("charset", "UTF-8"),
                    new Element("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1.0")
                );
            }
        }

        public static class Form extends Container {

            public Form() {
                super("form");
            }

            public Form action(String path) {
                attr("action", path);
                return this;
            }

            public Form method(String s) {
                attr("method", s);
                return this;
            }

            public Form encTypeTextPlain() {
                attr("enctype", "text/plain");
                return this;
            }

            public Form encTypeMultipartFormData() {
                attr("enctype", "multipart/form-data");
                return this;
            }

            public Form encTypeUrlEncoded() {
                attr("enctype", "application/x-www-form-urlencoded");
                return this;
            }

        }

        public static class ALink extends Container {
            public ALink() {
                super("a");
            }

            public ALink href(String link) {
                attr("href", link);
                return this;
            }

            public ALink targetBlank() {
                attr("target", "_blank");
                return this;
            }
        }

        public ALink a(Element... elements) {
            ALink v = new ALink();
            v.body(elements);
            return v;
        }

        public Form form(Element... elements) {
            Form v = new Form();
            v.body(elements);
            return v;
        }

        public Html html(Element... elements) {
            Html v = new Html();
            v.body(elements);
            return v;
        }

        public Html htmlAutoBody(Element... elements) {
//            html(code, "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Information</title></head><body>" + s + "</body></html>");
            return html(
                head(
                    meta().attr("charset", "UTF-8"),
                    meta().attr("name", "viewport").attr("content", "width=device-width,initial-scale=1")
                ),
                body(elements)
            );
        }

        public Container head(Element... elements) {
            return new Head().body(elements);
        }

        public Container title(String name) {
            return new Container("title").body(new InnerText(name));
        }

        public InnerText text(String s) {
            return new InnerText(s);
        }

        public Element meta() {
            return new Element("meta");
        }

        public Container body(Element... elements) {
            return new Container("body").body(elements);
        }

        public Container button(String s) {
            return new Container("button").body(text(s));
        }

        public Container div(Element... elements) {
            return new Container("div").body(elements);
        }

        public Container script(String js) {
            return new Container("script").body(new InnerText(js));
        }

        public Container scriptSrc(String src) {
            Container v = new Container("script");
            v.attr("src", src);
            return v;
        }

        public Container span(Element... elements) {
            return new Container("span").body(elements);
        }

        public static class Input extends Element {

            public Input() {
                super("input");
            }

            public Input name(String s) {
                attr("name", s);
                return this;
            }

            public Input value(String v) {
                attr("value", v);
                return this;
            }

            public Input type(String string) {
                attr("type", string);
                return this;
            }

            public Input multiple() {
                attr("multiple", "multiple");
                return this;
            }
        }

        private Input input() {
            return new Input();
        }

        public Input inputText() {
            return input().type("text");
        }

        public Input inputFile() {
            return input().type("file");
        }

        public Input inputSubmit() {
            return input().type("submit").value("submit");
        }

        public Element inputPassword() {
            return input().type("password");
        }

        public Container fieldSet(Element... elements) {
            return new Container("fieldset").body(elements);
        }

        public Container legend(String s) {
            return new Container("legend").body(text(s));
        }

        public Container h1(String s) {
            return new Container("h1").body(text(s));
        }

        public Container h2(String s) {
            return new Container("h2").body(text(s));
        }

        public Container h3(String s) {
            return new Container("h3").body(text(s));
        }

        public Container h4(String s) {
            return new Container("h4").body(text(s));
        }

        public Container h5(String s) {
            return new Container("h5").body(text(s));
        }

        public Container ul(Element... elements) {
            return new Container("ul").body(elements);
        }

        public Container ol(Element... elements) {
            return new Container("ol").body(elements);
        }

        public Container li(Element... elements) {
            return new Container("li").body(elements);
        }

        public Container p(Element... elements) {
            return new Container("p").body(elements);
        }

        public Container nav(Element... elements) {
            return new Container("nav").body(elements);
        }

        public static class Progress extends Container {

            public Progress() {
                super("progress");
            }

            public Progress max(int i) {
                attr("max", String.valueOf(i));
                return this;
            }

            public Progress value(int i) {
                attr("value", String.valueOf(i));
                return this;
            }
        }

        public Progress progress() {
            return new Progress();
        }

        public Element br() {
            return new Element("br");
        }

        public Element hr() {
            return new Element("hr");
        }

        public static class TextArea extends Container {

            public TextArea() {
                super("textarea");
            }

            public TextArea name(String s) {
                attr("name", s);
                return this;
            }

            public TextArea cols(int i) {
                attr("cols", String.valueOf(i));
                return this;
            }

            public TextArea rows(int i) {
                attr("rows", String.valueOf(i));
                return this;
            }
        }

        public TextArea textArea() {
            return new TextArea();
        }
    }


    public static class NetX {
        public static List<String> getIPs(boolean ipv6) throws SocketException {
            List<String> list = new ArrayList<>();
            List<String> listV6 = new ArrayList<>();

            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (inetAddress.isLoopbackAddress()) {
                        continue;
                    }
                    String hostAddress = inetAddress.getHostAddress();
                    if (hostAddress == null) {
                        continue;
                    }
                    if (hostAddress.contains(":")) {
                        //ipv6
                        int delimit = hostAddress.indexOf('%'); // drop ip6 zone suffix}
                        String s = delimit < 0 ? hostAddress.toUpperCase() : hostAddress.substring(0, delimit).toUpperCase();
                        listV6.add(s);
                    } else {
                        //ipv4
                        list.add(hostAddress);
                    }
                }
            }
            if (ipv6) {
                list.addAll(listV6);
            }

            return list;
        }
    }

}

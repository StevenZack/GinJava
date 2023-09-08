import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class Gin {

    private volatile boolean isRunning = false;
    private ServerSocket serverSocket;
    private final Map<String, Map<String, Handler>> handlerMap = new HashMap<>();

    public Gin() {

    }

    public void get(String path, Handler handler) {
        Map<String, Handler> v = handlerMap.get(path);
        if (v == null) {
            v = new HashMap<>();
            handlerMap.put(path, v);
        }
        v.put(Methods.get, handler);
    }

    public void post(String path, Handler handler) {
        Map<String, Handler> v = handlerMap.get(path);
        if (v == null) {
            v = new HashMap<>();
            handlerMap.put(path, v);
        }
        v.put(Methods.post, handler);
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

    private void run(int port) throws IOException {
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

    private void serveHTTP(Socket socket) {
        Context context = null;
        try {
            context = new Context(socket);
            if (handlerMap.containsKey(context.request.requestURI)) {
                Map<String, Handler> m = handlerMap.get(context.request.requestURI);
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
        private long bodyReadN = 0;

        public Context(Socket socket) throws Exception {
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
    }

    public static class Request {
        public String method;
        public String requestURI;
        public String proto;

        public Map<String, String> headers = new HashMap<>();

        private Context context;

        public Request() {

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
            File fo = new File(dst);
            if (fo.exists()) {
                fo.delete();
            }
            fo.createNewFile();
            FileOutputStream outputStream = new FileOutputStream(fo);
            context.readBytesUntil(outputStream, "\r\n\r\n".getBytes("UTF-8"));
            outputStream.close();
        }
    }

    public static class Response {
        public int statusCode = Status.ok;
        public String proto = Protocol.HTTP_1_1;

        public Map<String, String> headers = new HashMap<>();

        private ByteArrayOutputStream body;
        private InputStream bodyInputStream;

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
                int c = 0;
                while ((c = bodyInputStream.read()) != -1) {
                    context.outputStream.write(c);
                }
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
            string(Status.notFound, "404 not found");
        }

        public void internalServerError(String err) throws IOException {
            string(Status.internalServerError, "500 Internal Server Error: " + err);
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
        public static final int notFound = 404;
        public static final int methodNotAllowed = 405;
        public static final int internalServerError = 500;
        public static final int badRequest = 400;

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
        public static void truncateFile(String path) {
            File file = new File(path);
            file.getParentFile().mkdirs();
            if (file.exists()) {
                file.delete();
            }
        }

        public static String joinPath(String dir, String name) {
            if (dir.endsWith("/")) {
                return dir + name;
            }
            return dir + "/" + name;
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
                    builder.append(element.marshal());
                }
                builder.append("</").append(tagName).append(">");
                return builder.toString();
            }

            public Container body(Element... elements) {
                children.addAll(Arrays.asList(elements));
                return this;
            }

            public Container setBody(Element... elements) {
                children.clear();
                children.addAll(Arrays.asList(elements));
                return this;
            }
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

        public static class Form extends Container{

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

            public Form encTypeUrlEncoded(){
                attr("enctype", "application/x-www-form-urlencoded");
                return this;
            }

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

        public Container span(Element... elements) {
            return new Container("span").body(elements);
        }

        public static class Input extends Element {

            public Input() {
                super("input");
            }

            public Input value(String v) {
                attr("value", v);
                return this;
            }

            public Input type(String string) {
                attr("type", string);
                return this;
            }

        }

        private Input input() {
            return new Input();
        }

        public Element inputText() {
            return input().type("text");
        }

        public Input inputSubmit(){
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

    }
}
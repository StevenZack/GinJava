import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Main extends Gin.DSL {
    public static void main(String[] args) {
        new Main().start();
        try {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public void start() {
        Gin r = new Gin();
        r.get("/",c->{
            c.html(200,html(
                    head(title("OK")),
                    body(
                            h1("hello"),
                            fieldSet(
                                    legend("form"),
                                    form(
                                            inputText(),
                                            inputSubmit()
                                    ).action("/").method("post")
                            )
                    )
            ));
        });
        r.post("/",c->{
            System.out.println("post");
            throw new Exception("wtf");
        });
        r.listen(8080);
    }
}

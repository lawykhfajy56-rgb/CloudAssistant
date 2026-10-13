package com.khafaji.translator.t7db9413c.overlay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AIClient {

    public interface Callback {
        void onResult(String reply);
        void onError(String error);
    }

    // ضع مفتاح Gemini هنا (من aistudio.google.com). إذا تركته كما هو يُستخدم OpenAI
    private static final String GEMINI_KEY = "PUT_GEMINI_KEY_HERE";
    private static final String OPENAI_KEY = "PUT_OPENAI_KEY_HERE";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String OPENAI_MODEL = "gpt-4.1-mini";

    private static final String SYSTEM =
            "أنت السحابة، مساعد ذكي على هاتف المستخدم. جاوب دائماً بالعربية وبشكل مختصر في جملتين أو ثلاث، "
            + "لأن ردك يُقرأ بصوت عالٍ. لا تستخدم رموزاً أو تنسيقاً.";

    // عدد الأدوار المحفوظة (كل دور = سؤال + جواب)
    private static final int MAX_TURNS = 10;
    // كل عنصر = {الدور, النص}، والدور "user" أو "model"
    private static final List<String[]> HISTORY = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void ask(final String text, final Callback callback) {
        new Thread(() -> {
            try {
                boolean useGemini = !GEMINI_KEY.startsWith("PUT_");
                String reply = useGemini ? callGemini(text) : callOpenAi(text);
                remember(text, reply);
                MAIN.post(() -> callback.onResult(reply));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
                MAIN.post(() -> callback.onError(msg));
            }
        }).start();
    }

    public static void clearMemory() {
        synchronized (HISTORY) {
            HISTORY.clear();
        }
    }

    private static void remember(String userText, String reply) {
        synchronized (HISTORY) {
            HISTORY.add(new String[]{"user", userText});
            HISTORY.add(new String[]{"model", reply});
            while (HISTORY.size() > MAX_TURNS * 2) {
                HISTORY.remove(0);
            }
        }
    }

    private static List<String[]> snapshot() {
        synchronized (HISTORY) {
            return new ArrayList<>(HISTORY);
        }
    }

    private static String callGemini(String text) throws Exception {
        JSONArray contents = new JSONArray();
        for (String[] m : snapshot()) {
            contents.put(new JSONObject()
                    .put("role", m[0])
                    .put("parts", new JSONArray().put(new JSONObject().put("text", m[1]))));
        }
        contents.put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", text))));

        JSONObject body = new JSONObject()
                .put("contents", contents)
                .put("system_instruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM))));

        String resp = post("https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_MODEL + ":generateContent",
                "x-goog-api-key", GEMINI_KEY, body.toString());

        return new JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
    }

    private static String callOpenAi(String text) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM));
        for (String[] m : snapshot()) {
            String role = m[0].equals("model") ? "assistant" : "user";
            messages.put(new JSONObject().put("role", role).put("content", m[1]));
        }
        messages.put(new JSONObject().put("role", "user").put("content", text));

        JSONObject body = new JSONObject().put("model", OPENAI_MODEL).put("messages", messages);

        String resp = post("https://api.openai.com/v1/chat/completions",
                "Authorization", "Bearer " + OPENAI_KEY, body.toString());

        return new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
    }

    private static String post(String urlStr, String headerName, String headerValue, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(40000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty(headerName, headerValue);

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + sb);
        }
        return sb.toString();
    }
}

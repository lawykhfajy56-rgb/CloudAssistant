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

    private static final String BUSY = "الخدمة مشغولة حالياً، حاول بعد لحظات.";

    // ضع مفاتيحك هنا. أي مفتاح يبقى PUT_... يتم تجاوز مزوده تلقائياً
    private static final String GEMINI_KEY = "PUT_GEMINI_KEY_HERE";   // aistudio.google.com
    private static final String OPENAI_KEY = "PUT_OPENAI_KEY_HERE";   // platform.openai.com

    // ترتيب النماذج: الأسرع أولاً. عند فشل نموذج ينتقل التطبيق للذي يليه
    // النماذج التي تفكّر طويلاً (مثل gpt-5-mini) وضعناها في آخر القائمة لتقليل زمن الانتظار
    private static final String[][] MODELS = {
            {"g", "gemini-2.5-flash"},
            {"o", "gpt-4.1-mini"},
            {"g", "gemini-3.5-flash-lite"},
            {"g", "gemini-3.1-flash-lite"},
            {"o", "gpt-5-mini"},
            {"g", "gemini-3.6-flash"}
    };

    private static final String SYSTEM =
            "أنت الخفاجي ai (ويمكن للمستخدم مناداتك بكلودي أيضاً)، مساعد ذكي على هاتف المستخدم. "
            + "جاوب دائماً بالعربية وبشكل مختصر وواضح في جملتين أو ثلاث، لأن ردك يُقرأ بصوت عالٍ. "
            + "لا تستخدم رموزاً أو تنسيقاً.";

    // عدد الأدوار المحفوظة (كل دور = سؤال + جواب)
    private static final int MAX_TURNS = 10;

    // ذاكرة المحادثة: كل عنصر = {الدور, النص}، والدور إما "user" أو "model"
    private static final List<String[]> HISTORY = new ArrayList<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // الدالة الرئيسية: تُستدعى من الخدمة كما هي
    public static void ask(final String text, final Callback callback) {
        step(0, 0, text, new Callback() {
            @Override
            public void onResult(String reply) {
                remember(text, reply);
                callback.onResult(reply);
            }

            @Override
            public void onError(String err) {
                callback.onError(err);
            }
        });
    }

    // استدعِها عندما يطلب المستخدم "محادثة جديدة" لمسح الذاكرة
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

    // جرّب النموذج i؛ عند الفشل انتقل للتالي. بعد نهاية القائمة ننتظر 3 ثوانٍ ونعيد المحاولة مرة واحدة
    private static void step(final int i, final int round, final String text, final Callback callback) {
        if (i >= MODELS.length) {
            if (round < 1) {
                MAIN.postDelayed(() -> step(0, round + 1, text, callback), 3000L);
            } else {
                callback.onError(BUSY);
            }
            return;
        }

        String[] m = MODELS[i];
        boolean gemini = m[0].equals("g");
        String key = gemini ? GEMINI_KEY : OPENAI_KEY;
        if (key.startsWith("PUT_")) {
            step(i + 1, round, text, callback);
            return;
        }

        call(gemini, m[1], key, text, new Callback() {
            @Override
            public void onResult(String reply) {
                callback.onResult(reply);
            }

            @Override
            public void onError(String err) {
                step(i + 1, round, text, callback);
            }
        });
    }

    private static void call(final boolean gemini, final String model, final String key,
                             final String text, final Callback callback) {
        new Thread(() -> {
            try {
                String url = gemini
                        ? "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
                        : "https://api.openai.com/v1/chat/completions";

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(25000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                if (gemini) {
                    conn.setRequestProperty("x-goog-api-key", key);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                }
                conn.setDoOutput(true);

                List<String[]> past = snapshot();
                JSONObject body = new JSONObject();

                if (gemini) {
                    JSONArray contents = new JSONArray();
                    for (String[] m : past) {
                        contents.put(new JSONObject()
                                .put("role", m[0])
                                .put("parts", new JSONArray().put(new JSONObject().put("text", m[1]))));
                    }
                    contents.put(new JSONObject()
                            .put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
                    body.put("contents", contents);
                    body.put("system_instruction", new JSONObject()
                            .put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM))));
                    JSONObject gen = new JSONObject().put("maxOutputTokens", 300);
                    // إيقاف التفكير الداخلي في نماذج 2.5 لتسريع الرد
                    if (model.startsWith("gemini-2.5")) {
                        gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
                    }
                    body.put("generationConfig", gen);
                } else {
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "system").put("content", SYSTEM));
                    for (String[] m : past) {
                        String role = m[0].equals("model") ? "assistant" : "user";
                        messages.put(new JSONObject().put("role", role).put("content", m[1]));
                    }
                    messages.put(new JSONObject().put("role", "user").put("content", text));
                    body.put("model", model).put("messages", messages);
                }

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
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

                JSONObject json = new JSONObject(sb.toString());
                final String reply = gemini
                        ? json.getJSONArray("candidates").getJSONObject(0)
                              .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                        : json.getJSONArray("choices").getJSONObject(0)
                              .getJSONObject("message").getString("content");

                MAIN.post(() -> callback.onResult(reply));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
                MAIN.post(() -> callback.onError(msg));
            }
        }).start();
    }
}

package com.hackathon.setscan;

import android.graphics.Bitmap;
import android.util.Base64;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Ask Claude" after a scan: sends a few room photos plus a crop of every object the scan found to Claude,
 * which names the objects properly and suggests shots and lighting for the room. Runs on a background thread.
 */
final class ClaudeHelper {

    static final String MODEL = "claude-opus-5-5";

    /** What came back: names per object index, plus advice for the set. */
    static final class Result {
        final List<String> names = new ArrayList<>();
        String room = "", lighting = "";
        final List<String> shots = new ArrayList<>();
    }

    static String jpegBase64(Bitmap b, int maxSide) {
        float s = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
        Bitmap small = s < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true) : b;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 85, o);
        if (small != b) small.recycle();
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }

    static String jpegBase64(byte[] jpg) { return Base64.encodeToString(jpg, Base64.NO_WRAP); }

    private static BetaContentBlockParam image(String b64) {
        return BetaContentBlockParam.ofImage(BetaBase64ImageSource.builder()
                .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).data(b64).build());
    }

    /**
     * @param roomPhotos  base64 JPEGs of the room (a handful)
     * @param objectCrops base64 JPEG per object, same order as objectInfo
     * @param objectInfo  one line per object: size, height, what it stands on, the on-device guess
     */
    static Result ask(String apiKey, String roomSummary, List<String> roomPhotos, List<String> objectCrops, List<String> objectInfo) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        try {
            List<BetaContentBlockParam> content = new ArrayList<>();
            content.add(BetaContentBlockParam.ofText("Overview photos of the scanned room:"));
            for (String p : roomPhotos) content.add(image(p));
            for (int i = 0; i < objectCrops.size(); i++) {
                content.add(BetaContentBlockParam.ofText("Object " + i + ": " + objectInfo.get(i)));
                content.add(image(objectCrops.get(i)));
            }
            content.add(BetaContentBlockParam.ofText(
                    "These come from CineX, a phone app that scans a real room so ordinary people can plan a film or video shoot in it. "
                    + "The 3D scan found the objects above (each crop is the best photo of that object; it may be partly wrong or show a neighbouring thing). "
                    + "Scan summary: " + roomSummary + "\n\n"
                    + "Reply with only a JSON object, no other text:\n"
                    + "{\"objects\": [{\"id\": 0, \"name\": \"2-4 word name, e.g. white leather sneakers\"}, ...one per object],\n"
                    + " \"room\": \"one sentence on what kind of room this is\",\n"
                    + " \"lighting\": \"one or two sentences on the light in the room and how to improve it for filming\",\n"
                    + " \"shots\": [\"three short shot ideas that fit this room and its objects\"]}\n"
                    + "If a crop shows nothing recognisable, name it \"unclear\"."));
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(16000L)
                    .addUserMessageOfContentBlockParams(content)
                    // if a safety check declines the request, the API retries it on a fallback model by itself
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build();
            BetaMessage msg = client.beta().messages().create(params);
            if (msg.stopReason().isPresent() && msg.stopReason().get().equals(BetaStopReason.REFUSAL))
                throw new IllegalStateException("Claude declined this request");
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock b : msg.content()) b.text().ifPresent(t -> text.append(t.text()));
            return parse(text.toString(), objectCrops.size());
        } finally {
            client.close();
        }
    }

    static Result parse(String text, int n) throws Exception {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("no JSON in the reply");
        JSONObject j = new JSONObject(text.substring(a, b + 1));
        Result r = new Result();
        for (int i = 0; i < n; i++) r.names.add(null);
        JSONArray objs = j.optJSONArray("objects");
        if (objs != null) for (int i = 0; i < objs.length(); i++) {
            JSONObject o = objs.getJSONObject(i);
            int id = o.optInt("id", -1);
            String name = o.optString("name", "").trim();
            if (id >= 0 && id < n && !name.isEmpty() && !name.toLowerCase(Locale.US).equals("unclear")) r.names.set(id, name);
        }
        r.room = j.optString("room", "");
        r.lighting = j.optString("lighting", "");
        JSONArray shots = j.optJSONArray("shots");
        if (shots != null) for (int i = 0; i < shots.length(); i++) r.shots.add(shots.getString(i));
        return r;
    }
}

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
    /** Categories the app has a simple cartoon model for. */
    static final String KINDS = "table, desk, chair, stool, bed, sofa, shelf, cabinet, wardrobe, shoe, bottle, can, cup, vase, lamp, plant, bag, screen, box, pole, other, noise";

    /** What came back: names per object index, plus advice for the set. */
    static final class Result {
        final List<String> names = new ArrayList<>(), kinds = new ArrayList<>();
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
                    + "{\"objects\": [{\"id\": 0, \"name\": \"2-4 word name, e.g. white leather sneakers\", \"kind\": \"one of: " + KINDS + "\"}, ...one per object],\n"
                    + " \"room\": \"what kind of room, max 6 words\",\n"
                    + " \"lighting\": \"one lighting tip for filming here, max 15 words\",\n"
                    + " \"shots\": [\"three shot ideas, max 8 words each\"]}\n"
                    + "Use kind \"noise\" when the crop shows nothing real (empty floor, a blurry fragment of a wall), so the app can delete it.\n"
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

    /**
     * Live: one current photo with numbered boxes drawn on it; Claude says what each numbered thing is.
     * Low effort keeps the answer quick (a few seconds) while scanning.
     */
    static Result askLive(String apiKey, String photoB64, List<String> objectInfo) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        try {
            List<BetaContentBlockParam> content = new ArrayList<>();
            content.add(image(photoB64));
            StringBuilder t = new StringBuilder("A phone is 3D-scanning a room to plan a video shoot. The scan found the numbered boxes drawn on this photo:\n");
            for (int i = 0; i < objectInfo.size(); i++) t.append(i).append(": ").append(objectInfo.get(i)).append('\n');
            t.append("For each number, say what the thing inside that box is. Reply with only JSON, no other text:\n")
             .append("{\"objects\": [{\"id\": 0, \"name\": \"1-3 word name\", \"kind\": \"one of: ").append(KINDS).append("\"}]}\n")
             .append("Use kind \"noise\" when a box holds nothing real (empty floor, a fragment of wall, a shadow).");
            content.add(BetaContentBlockParam.ofText(t.toString()));
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(4000L)
                    .outputConfig(com.anthropic.models.beta.messages.BetaOutputConfig.builder()
                            .effort(com.anthropic.models.beta.messages.BetaOutputConfig.Effort.LOW).build())
                    .addUserMessageOfContentBlockParams(content)
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build();
            BetaMessage msg = client.beta().messages().create(params);
            if (msg.stopReason().isPresent() && msg.stopReason().get().equals(BetaStopReason.REFUSAL))
                throw new IllegalStateException("Claude declined this request");
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock b : msg.content()) b.text().ifPresent(x -> text.append(x.text()));
            return parse(text.toString(), objectInfo.size());
        } finally {
            client.close();
        }
    }

    static Result parse(String text, int n) throws Exception {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("no JSON in the reply");
        JSONObject j = new JSONObject(text.substring(a, b + 1));
        Result r = new Result();
        for (int i = 0; i < n; i++) { r.names.add(null); r.kinds.add(null); }
        JSONArray objs = j.optJSONArray("objects");
        if (objs != null) for (int i = 0; i < objs.length(); i++) {
            JSONObject o = objs.getJSONObject(i);
            int id = o.optInt("id", -1);
            String name = o.optString("name", "").trim();
            if (id < 0 || id >= n) continue;
            String kind = o.optString("kind", "").trim().toLowerCase(Locale.US);
            if (!kind.isEmpty()) r.kinds.set(id, kind);
            if (!name.isEmpty() && !name.toLowerCase(Locale.US).equals("unclear")) r.names.set(id, name);
        }
        r.room = j.optString("room", "");
        r.lighting = j.optString("lighting", "");
        JSONArray shots = j.optJSONArray("shots");
        if (shots != null) for (int i = 0; i < shots.length(); i++) r.shots.add(shots.getString(i));
        return r;
    }
}

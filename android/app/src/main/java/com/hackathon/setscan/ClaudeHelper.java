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
    /** Estimated spend since the app started, in US dollars (Claude Opus 5.5 list price: $4 in / $20 out per million tokens). */
    static volatile double sessionUsd;
    static volatile int sessionCalls;
    static volatile Runnable onSpend; // called after every request (any thread), for the money counter

    private static void count(BetaMessage msg) {
        com.anthropic.models.beta.messages.BetaUsage u = msg.usage();
        long in = u.inputTokens() + u.cacheCreationInputTokens().orElse(0L), cached = u.cacheReadInputTokens().orElse(0L);
        long written = u.cacheCreationInputTokens().orElse(0L);
        // Opus 5.5: $4 / MTok in, $5 cache writes, $0.20 cache reads, $20 out
        sessionUsd += ((in - written) * 4.0 + written * 5.0 + cached * 0.2 + u.outputTokens() * 20.0) / 1_000_000.0;
        sessionCalls++;
        Runnable r = onSpend; if (r != null) r.run();
    }

    static String costLine() {
        return sessionCalls == 0 ? "" : String.format(Locale.US, "Claude: %d call%s, about $%.2f this session", sessionCalls, sessionCalls == 1 ? "" : "s", sessionUsd);
    }

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
            count(msg);
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
            count(msg);
            if (msg.stopReason().isPresent() && msg.stopReason().get().equals(BetaStopReason.REFUSAL))
                throw new IllegalStateException("Claude declined this request");
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock b : msg.content()) b.text().ifPresent(x -> text.append(x.text()));
            return parse(text.toString(), objectInfo.size());
        } finally {
            client.close();
        }
    }

    /** One thing Claude found in the live photo: its name, kind and box in the photo (0..1, top-left origin). */
    static final class Det { String name, kind, on = ""; float x0, y0, x1, y1, sw, sd, sh; }

    /**
     * Live detection: Claude finds the objects in the newest photo itself and boxes them; the app then lifts each box
     * into 3D with the scan points behind it. This doesn't depend on the scan having split things up correctly.
     */
    static List<Det> detectLive(String apiKey, String photoB64) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        try {
            List<BetaContentBlockParam> content = new ArrayList<>();
            content.add(image(photoB64));
            content.add(BetaContentBlockParam.ofText(
                    "A phone is 3D-scanning a room to plan a video shoot; this is its newest photo. "
                    + "Find each distinct physical object in it that is at least half visible: furniture, things on furniture, things on the floor. "
                    + "Skip walls, floor, ceiling, doors, windows, rugs, shadows, light patches, people and body parts.\n"
                    + "Reply with only JSON, no other text:\n"
                    + "{\"objects\": [{\"name\": \"1-3 word name\", \"kind\": \"one of: " + KINDS + "\", \"box\": [x0, y0, x1, y1], \"size_cm\": [width, depth, height], \"on\": \"floor, wall, or what it stands on (e.g. desk)\"}]}\n"
                    + "box is the tight bounding box in the photo in thousandths of the image width and height (0-1000, top-left origin). "
                    + "size_cm is your best estimate of the thing's real size, from what it is and how it compares with things around it. "
                    + "At most 10 objects, biggest first. A pair of shoes side by side can be one object."));
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
            count(msg);
            if (msg.stopReason().isPresent() && msg.stopReason().get().equals(BetaStopReason.REFUSAL))
                throw new IllegalStateException("Claude declined this request");
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock b : msg.content()) b.text().ifPresent(x -> text.append(x.text()));
            return parseDetect(text.toString());
        } finally {
            client.close();
        }
    }

    /** A power outlet Claude found: which photo (0-based), its box (0..1, top-left origin) and what kind. */
    static final class OutletDet { int photo; float x0, y0, x1, y1; String type = "wall socket"; int sockets = 1; boolean object; }

    /** Find every power outlet in a batch of room photos (one request for up to 6 photos). */
    static List<OutletDet> detectOutlets(String apiKey, List<String> photosB64) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        try {
            List<BetaContentBlockParam> content = new ArrayList<>();
            for (int i = 0; i < photosB64.size(); i++) {
                content.add(BetaContentBlockParam.ofText("Photo " + i + ":"));
                content.add(image(photosB64.get(i)));
            }
            content.add(BetaContentBlockParam.ofText(
                    "These photos come from a phone scanning a room for a film shoot or an interior design job.\n"
                    + "1. Find every electrical power outlet: wall sockets, power strips / extension leads, floor sockets. "
                    + "Not light switches, network or antenna sockets, chargers, or plugs hanging in the air.\n"
                    + "2. Find every movable piece of furniture or large object standing in the room: chairs, tables, desks, sofas, beds, "
                    + "shelves, cabinets, drawers, floor lamps, plants, boxes, bins, bags, shoes, equipment. Not walls, floors, ceilings, doors, "
                    + "windows, radiators, curtains, or things fixed into the walls. Give each its own box, even when it touches something else.\n"
                    + "Only things you can actually see. Reply with only JSON, no other text:\n"
                    + "{\"outlets\": [{\"photo\": 0, \"box\": [x0, y0, x1, y1], \"type\": \"wall socket | power strip | floor socket\", \"sockets\": 1}], "
                    + "\"objects\": [{\"photo\": 0, \"box\": [x0, y0, x1, y1], \"name\": \"office chair\"}]}\n"
                    + "box is the tight bounding box in that photo, in thousandths of the image width and height (0-1000, top-left origin). "
                    + "sockets is how many plug positions it has. name is 1 to 3 plain words. Empty lists are fine."));
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(6000L)
                    .outputConfig(com.anthropic.models.beta.messages.BetaOutputConfig.builder()
                            .effort(com.anthropic.models.beta.messages.BetaOutputConfig.Effort.LOW).build())
                    .addUserMessageOfContentBlockParams(content)
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build();
            BetaMessage msg = client.beta().messages().create(params);
            count(msg);
            if (msg.stopReason().isPresent() && msg.stopReason().get().equals(BetaStopReason.REFUSAL))
                throw new IllegalStateException("Claude declined this request");
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock b : msg.content()) b.text().ifPresent(x -> text.append(x.text()));
            String t = text.toString();
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            List<OutletDet> out = new ArrayList<>();
            if (a < 0 || b <= a) return out;
            JSONObject root = new JSONObject(t.substring(a, b + 1));
            JSONArray outs = root.optJSONArray("outlets"), objs = root.optJSONArray("objects");
            int no = outs == null ? 0 : outs.length(), nj = objs == null ? 0 : objs.length();
            for (int i = 0; i < no + nj; i++) {
                boolean isObj = i >= no;
                JSONObject o = isObj ? objs.optJSONObject(i - no) : outs.optJSONObject(i);
                if (o == null) continue;
                JSONArray bx = o.optJSONArray("box");
                int ph = o.optInt("photo", -1);
                if (bx == null || bx.length() != 4 || ph < 0 || ph >= photosB64.size()) continue;
                OutletDet d = new OutletDet();
                d.photo = ph;
                float x0 = (float) bx.optDouble(0, -1), y0 = (float) bx.optDouble(1, -1), x1 = (float) bx.optDouble(2, -1), y1 = (float) bx.optDouble(3, -1);
                d.x0 = Math.max(0, Math.min(x0, x1)) / 1000f; d.x1 = Math.min(1000, Math.max(x0, x1)) / 1000f;
                d.y0 = Math.max(0, Math.min(y0, y1)) / 1000f; d.y1 = Math.min(1000, Math.max(y0, y1)) / 1000f;
                if (d.x1 <= d.x0 || d.y1 <= d.y0) continue;
                d.object = isObj;
                d.type = (isObj ? o.optString("name", "object") : o.optString("type", "wall socket")).trim().toLowerCase(Locale.US);
                d.sockets = Math.max(1, Math.min(12, o.optInt("sockets", 1)));
                out.add(d);
            }
            return out;
        } finally {
            client.close();
        }
    }

    /**
     * DEMO (hall from the stage): Claude estimates how far the back wall is, from photos looking out plus what the
     * phone did measure. Returns {back wall distance m, hall width m, ceiling height m} (NaN when unsure) or null.
     */
    static double[] estimateHall(String apiKey, List<String> photosB64, String measured) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        try {
            List<BetaContentBlockParam> content = new ArrayList<>();
            for (String b : photosB64) content.add(image(b));
            content.add(BetaContentBlockParam.ofText(
                    "These photos were taken from one spot (a stage or the front of a room), looking out into the space. "
                    + "The phone measured the near part in 3D but its sensor only reaches about 5 m, so the far end is unknown.\n"
                    + "What the phone measured:\n" + measured + "\n"
                    + "Estimate the distance from the camera to the far (back) wall straight ahead, in metres. Use every cue: "
                    + "the measured width and how wide the back wall looks compared with it, rows of chairs (about 0.9 m apart), "
                    + "doors (about 2.1 m tall), tables, people (about 1.75 m), ceiling lights, perspective. "
                    + "Reply with only JSON: {\"back_wall_m\": 0, \"width_m\": 0, \"ceiling_m\": 0, \"why\": \"one short sentence\"}"));
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(4000L)
                    .addUserMessageOfContentBlockParams(content)
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build();
            BetaMessage msg = client.beta().messages().create(params);
            count(msg);
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock b : msg.content()) b.text().ifPresent(x -> text.append(x.text()));
            String t = text.toString();
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            if (a < 0 || b <= a) return null;
            JSONObject o = new JSONObject(t.substring(a, b + 1));
            lastHallWhy = o.optString("why", "");
            return new double[]{o.optDouble("back_wall_m", Double.NaN), o.optDouble("width_m", Double.NaN), o.optDouble("ceiling_m", Double.NaN)};
        } finally {
            client.close();
        }
    }
    static volatile String lastHallWhy = "";

    static List<Det> parseDetect(String text) throws Exception {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("no JSON in the reply");
        JSONArray objs = new JSONObject(text.substring(a, b + 1)).optJSONArray("objects");
        List<Det> out = new ArrayList<>();
        if (objs == null) return out;
        for (int i = 0; i < objs.length(); i++) {
            JSONObject o = objs.optJSONObject(i);
            if (o == null) continue;
            JSONArray bx = o.optJSONArray("box");
            if (bx == null || bx.length() != 4) continue;
            Det d = new Det();
            d.name = o.optString("name", "").trim();
            d.kind = o.optString("kind", "other").trim().toLowerCase(Locale.US);
            float x0 = (float) bx.optDouble(0, -1), y0 = (float) bx.optDouble(1, -1), x1 = (float) bx.optDouble(2, -1), y1 = (float) bx.optDouble(3, -1);
            d.x0 = Math.max(0, Math.min(x0, x1)) / 1000f; d.x1 = Math.min(1000, Math.max(x0, x1)) / 1000f;
            d.y0 = Math.max(0, Math.min(y0, y1)) / 1000f; d.y1 = Math.min(1000, Math.max(y0, y1)) / 1000f;
            JSONArray sz = o.optJSONArray("size_cm");
            if (sz != null && sz.length() == 3) {
                d.sw = (float) sz.optDouble(0, 0) / 100f; d.sd = (float) sz.optDouble(1, 0) / 100f; d.sh = (float) sz.optDouble(2, 0) / 100f;
                if (d.sw <= 0 || d.sd <= 0 || d.sh <= 0 || d.sw > 4 || d.sd > 4 || d.sh > 3) d.sw = d.sd = d.sh = 0;
            }
            d.on = o.optString("on", "").trim().toLowerCase(Locale.US);
            if (d.name.isEmpty() || d.kind.equals("noise") || d.x1 - d.x0 < 0.01f || d.y1 - d.y0 < 0.01f) continue;
            out.add(d);
        }
        return out;
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

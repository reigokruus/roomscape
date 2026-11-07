package com.hackathon.setscan;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CatalogAndRoomEditsTest {
    private String seed() throws Exception {return new String(Files.readAllBytes(Paths.get("src/main/assets/equipment-catalog.json")),java.nio.charset.StandardCharsets.UTF_8);}
    private List<ItemCatalog.Item> items() throws Exception {return ItemCatalog.parse(seed());}
    private interface Throwing {void run() throws Exception;}
    private void rejected(Throwing t){try{t.run();fail("Invalid input accepted");}catch(Exception expected){assertNotNull(expected.getMessage());}}
    private JSONObject row() throws Exception {return ItemCatalog.object(seed()).getJSONArray("items").getJSONObject(0);}
    private String feed(JSONObject row) throws Exception {return new JSONObject().put("schemaVersion",1).put("items",new JSONArray().put(row)).toString();}
    @Test public void realSeedKeepsBodyDeployedAndAssembledDimensionsSeparate() throws Exception {
        List<ItemCatalog.Item> i=items();assertEquals(5,i.size());
        assertEquals(.1385,i.get(0).width,1e-7);assertEquals(.0975,i.get(0).height,1e-7);assertEquals(.088,i.get(0).depth,1e-7);
        assertEquals(.093,i.get(1).width,1e-7);assertEquals(.017,i.get(1).depth,1e-7);
        assertEquals("deployed",i.get(2).dimensionKind);assertEquals(1.09,i.get(2).width,1e-7);assertEquals(2.37,i.get(2).height,1e-7);
        assertEquals(35.375*.0254,i.get(3).width,1e-7);assertEquals(17.75*.0254,i.get(3).height,1e-7);assertEquals(21.625*.0254,i.get(3).depth,1e-7);
        assertFalse(i.get(4).complete());assertTrue(i.get(4).dimensions().contains("unknown"));
        for(ItemCatalog.Item item:i){assertTrue(item.sourceUrl.startsWith("https://"));assertEquals("2026-11-07",item.retrievedDate);assertFalse(item.sourceQuote.isEmpty());assertFalse(item.axisNotes.isEmpty());}
    }
    @Test public void searchMatchesExactModelBrandAndCategory() throws Exception {assertTrue(items().get(0).matches("eOs r5"));assertTrue(items().get(2).matches("stand"));assertFalse(items().get(0).matches("tripod"));}
    @Test public void cachedRoundTripPreservesSIAndProvenance() throws Exception {List<ItemCatalog.Item> a=items(),b=ItemCatalog.parse(ItemCatalog.encode(a));assertEquals(a.get(3).width,b.get(3).width);assertEquals(a.get(1).sourceQuote,b.get(1).sourceQuote);assertNull(b.get(4).width);}
    @Test public void unitsAreExplicitAndConvertExactly(){assertEquals(.0254,ItemCatalog.unitScale("in"),0);assertEquals(.001,ItemCatalog.unitScale("mm"),0);assertEquals(.01,ItemCatalog.unitScale("cm"),0);assertEquals(1,ItemCatalog.unitScale("m"),0);rejected(()->ItemCatalog.unitScale("feet"));}
    @Test public void packageDimensionsAndDuplicateIdsAreRejected() throws Exception {JSONObject r=ItemCatalog.object(seed());r.getJSONArray("items").put(row());rejected(()->ItemCatalog.parse(r.toString()));JSONObject p=row().put("dimensionKind","package");rejected(()->ItemCatalog.parse(feed(p)));}
    @Test public void missingDimensionsStayUnknownRatherThanGuessed() throws Exception {JSONObject p=row();p.remove("height");ItemCatalog.Item i=ItemCatalog.parse(feed(p)).get(0);assertNull(i.height);assertFalse(i.complete());}
    @Test public void invalidDimensionsDatesAndCredentialsAreRejected() throws Exception {
        for(Object n:new Object[]{-1,0,100000,"138.5"}){JSONObject p=row().put("width",n);rejected(()->ItemCatalog.parse(feed(p)));}
        for(String date:new String[]{"2026-02-30","2026-1-1","2999-01-01"}){JSONObject p=row().put("retrievedDate",date);rejected(()->ItemCatalog.parse(feed(p)));}
        JSONObject p=row().put("sourceUrl","https://user:secret@example.com/spec");rejected(()->ItemCatalog.parse(feed(p)));
    }
    @Test public void onlyRepositoryHttpsJSONFeedsAreFetched() throws Exception {
        assertEquals("raw.githubusercontent.com",ItemCatalog.feedUri(ItemCatalog.DEFAULT_FEED).getHost());
        for(String s:new String[]{"http://raw.githubusercontent.com/reigokruus/shootlocal/a.json","https://localhost/x.json","https://127.0.0.1/a.json","https://raw.githubusercontent.com/other/repo/a.json","https://raw.githubusercontent.com/reigokruus/shootlocal/a.html","https://raw.githubusercontent.com/reigokruus/shootlocal/a.json?token=secret","https://raw.githubusercontent.com/reigokruus/shootlocal/../other/a.json"})rejected(()->ItemCatalog.feedUri(s));
    }
    @Test public void oversizedDeepMalformedAndInvalidUTF8ImportsAreRejected() throws Exception {
        rejected(()->ItemCatalog.parse(" ".repeat(ItemCatalog.MAX_BYTES+1)));rejected(()->ItemCatalog.parse("{broken"));rejected(()->ItemCatalog.parse(seed()+" trailing"));
        rejected(()->ItemCatalog.object("[".repeat(1000)+"]".repeat(1000)));
        rejected(()->ItemCatalog.read(new ByteArrayInputStream(new byte[ItemCatalog.MAX_BYTES+1])));
        rejected(()->ItemCatalog.read(new ByteArrayInputStream(new byte[]{(byte)0xc0,(byte)0xaf})));
    }
    @Test public void mergeIsIdempotentAndDoesNotDropUnrelatedModels() throws Exception {List<ItemCatalog.Item> a=items();assertEquals(5,ItemCatalog.merge(a,a).size());assertEquals(5,ItemCatalog.merge(a,Collections.singletonList(a.get(0))).size());}
    @Test public void idModelCollisionsAndOlderFactsCannotReplaceCache() throws Exception {
        List<ItemCatalog.Item> a=items();JSONObject changed=row().put("model","Different body");rejected(()->ItemCatalog.merge(a,ItemCatalog.parse(feed(changed))));
        JSONObject stale=row().put("retrievedDate","2026-11-06");rejected(()->ItemCatalog.merge(a,ItemCatalog.parse(feed(stale))));assertEquals("EOS R5 (body only)",a.get(0).model);
    }
    @Test public void badImportHasNoPartialMergeSideEffects() throws Exception {List<ItemCatalog.Item> old=items();String before=ItemCatalog.encode(old);JSONObject bad=row().put("sourceUrl","file:///secret");rejected(()->ItemCatalog.merge(old,ItemCatalog.parse(feed(bad))));assertEquals(before,ItemCatalog.encode(old));}
    @Test public void catalogPlacementUsesRealSizeWithoutInventedLightOrLensBehavior() throws Exception {
        ItemCatalog.Item camera=items().get(0);Lighting.Prop p=Lighting.catalogProp(camera,null,1.2f,2,3);assertArrayEquals(new double[]{.1385,.0975,.088},p.size(),1e-7);assertEquals(Lighting.PropKind.CATALOG,p.kind);assertFalse(p.isPerson());assertEquals(1.2975,p.faceH(),1e-5);assertTrue(p.label().contains("sourced"));
    }
    @Test public void unknownSizeRequiresSeparateValidatedManualOverride() throws Exception {
        ItemCatalog.Item unknown=items().get(4);rejected(()->Lighting.catalogProp(unknown,null,0,0,0));double[] manual={.2,.12,.1};Lighting.Prop p=Lighting.catalogProp(unknown,manual,0,0,0);manual[0]=9;assertEquals(.2,p.size()[0],0);assertNull(unknown.width);assertTrue(p.label().contains("MANUAL"));
        rejected(()->Lighting.catalogProp(unknown,new double[]{Double.NaN,1,1},0,0,0));rejected(()->Lighting.catalogProp(unknown,new double[]{1,1},0,0,0));
    }
    private RoomModel.Box box(String name,float x){RoomModel.Box b=new RoomModel.Box();b.name=name;b.cx=x;b.cz=1;b.hu=.5f;b.hv=.3f;b.y0=0;b.y1=.8f;b.cosA=1; b.updateAabb();return b;}
    @Test public void hideActuallyTargetsDetectedScanFurnitureNotOnlyAddedProps() {
        RoomModel.Box table=box("table",1),chair=box("chair",3),wall=box("wall",5);wall.kind="wall";List<RoomModel.Box> original=new ArrayList<>(Arrays.asList(table,chair,wall));RoomEdits e=new RoomEdits();e.capture(original);assertEquals(2,e.detected.size());
        String id=e.detected.get(0).id;e.hide(id,true);assertEquals(1,e.visible().size());assertEquals("chair",e.visible().get(0).title);assertEquals(3,original.size());assertEquals(.5,table.hu,0);assertEquals("table",table.name);assertTrue(e.undo());assertEquals(2,e.visible().size());
    }
    @Test public void restoreAllAndUndoAreNonDestructiveAndBounded() {
        RoomEdits e=new RoomEdits();e.capture(Collections.singletonList(box("table",1)));String id=e.detected.get(0).id;for(int i=0;i<40;i++)e.hide(id,i%2==0);int n=0;while(e.undo())n++;assertEquals(30,n);e.hide(id,true);e.restoreAll();assertFalse(e.hidden.contains(id));assertTrue(e.undo());assertTrue(e.hidden.contains(id));rejected(()->e.hide("unknown-id",true));
    }
    @Test public void planningSnapshotStaysStableWhenScanIsReanalysed(){RoomEdits e=new RoomEdits();e.capture(Collections.singletonList(box("chair",2)));String id=e.detected.get(0).id;e.hide(id,true);e.capture(Collections.singletonList(box("new shape",9)));assertEquals(id,e.detected.get(0).id);assertEquals(0,e.visible().size());}
    @Test public void hiddenDetectedObjectsAndPlacementProvenancePersistAndExportTogether() throws Exception {
        RoomEdits e=new RoomEdits();e.capture(Collections.singletonList(box("table",1)));String id=e.detected.get(0).id;e.hide(id,true);Lighting l=new Lighting();l.props.add(Lighting.catalogProp(items().get(0),new double[]{.3,.2,.1},1.2f,2,3));
        String exported=e.encode(l);Lighting reloaded=new Lighting();RoomEdits r=RoomEdits.decode(exported,reloaded);assertTrue(r.hidden.contains(id));assertEquals(0,r.visible().size());assertEquals(1,reloaded.props.size());assertArrayEquals(new double[]{.3,.2,.1},reloaded.props.get(0).size(),0);assertEquals(.1385,reloaded.props.get(0).catalog.width,1e-7);assertEquals(l.props.get(0).instanceId,reloaded.props.get(0).instanceId);assertFalse(r.canUndo());r.restoreAll();assertEquals(1,r.visible().size());
    }
    @Test public void corruptPlanIsRejectedBeforeReplacingExistingPlacements() throws Exception {
        RoomEdits e=new RoomEdits();e.capture(Collections.singletonList(box("table",1)));Lighting l=new Lighting();l.props.add(Lighting.catalogProp(items().get(0),null,0,0,0));JSONObject corrupt=new JSONObject(e.encode(l));corrupt.getJSONArray("hiddenIds").put("not-a-detected-id");rejected(()->RoomEdits.decode(corrupt.toString(),l));assertEquals(1,l.props.size());
        JSONObject duplicate=new JSONObject(e.encode(l));duplicate.getJSONArray("placements").put(duplicate.getJSONArray("placements").getJSONObject(0));rejected(()->RoomEdits.decode(duplicate.toString(),l));assertEquals(1,l.props.size());
    }
    @Test public void largeDetectedSetsDoNotSilentlyDropObjects() {
        RoomEdits e=new RoomEdits();List<RoomModel.Box> boxes=new ArrayList<>();for(int i=0;i<201;i++)boxes.add(box("chair",i));rejected(()->e.capture(boxes));assertFalse(e.captured);assertTrue(e.detected.isEmpty());
    }
    @Test public void equipmentSizeProxyNeverBecomesLightingFaceSubject() throws Exception {
        Lighting l=new Lighting();l.props.add(Lighting.catalogProp(items().get(0),null,1,0,0));assertNull(l.subjectFace());assertEquals("Add a person to see the light on their face.",l.readout());
    }
    @Test public void mergedCacheCannotExceedItsOwnReadLimit() throws Exception {
        List<ItemCatalog.Item> old=new ArrayList<>(),incoming=new ArrayList<>();
        for(int i=0;i<200;i++){JSONObject j=row().put("id","model-"+i).put("sourceQuote","Q".repeat(1100)).put("axisNotes","A".repeat(1100));(i<100?old:incoming).add(new ItemCatalog.Item(j));}
        assertTrue(ItemCatalog.encode(old).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < ItemCatalog.MAX_BYTES);
        rejected(()->ItemCatalog.merge(old,incoming));assertEquals(100,old.size());
    }
    @Test public void tooManyRowsAndAmbiguousSchemaAreRejected() throws Exception {
        JSONArray rows=new JSONArray();for(int i=0;i<201;i++)rows.put(row().put("id","model-"+i));String many=new JSONObject().put("schemaVersion",1).put("items",rows).toString();rejected(()->ItemCatalog.parse(many));
        JSONObject fractional=ItemCatalog.object(seed()).put("schemaVersion",1.5);rejected(()->ItemCatalog.parse(fractional.toString()));
    }
    @Test public void structuralPhotoLabelsDoNotBecomeRemovableFurniture() {
        RoomEdits e=new RoomEdits();e.capture(Arrays.asList(box("wall",0),box("floor",1),box("window",2),box("table",3)));assertEquals(1,e.detected.size());assertEquals("table",e.detected.get(0).title);
    }
}

package com.hackathon.setscan;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

/** Small native catalog browser and planning-object controls. All room mutations go to the GL owner. */
final class NativePlanningUi {
    static final int IMPORT_CATALOG=6219;
    private final MainActivity a;
    private final GLSurfaceView gl;
    private final ScanRenderer renderer;
    private final CatalogStore catalog;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    NativePlanningUi(MainActivity a,GLSurfaceView gl,ScanRenderer renderer){this.a=a;this.gl=gl;this.renderer=renderer;catalog=new CatalogStore(a);}
    void close(){io.shutdownNow();}
    private void ui(Runnable r){a.runOnUiThread(()->{if(!a.isDestroyed())r.run();});}
    private void error(Exception e){ui(()->new AlertDialog.Builder(a).setTitle("Nothing imported / not saved").setMessage(String.valueOf(e.getMessage())+"\nExisting source data and original scan have not been deleted.").setPositiveButton("OK",null).show());}
    private void work(Work w){if(!io.isShutdown())io.execute(()->{try{w.run();}catch(Exception e){error(e);}});}
    private interface Work{void run() throws Exception;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(24,12,24,12);return l;}
    private TextView text(String s){TextView t=new TextView(a);t.setText(s);t.setPadding(0,8,0,8);return t;}
    private Button button(String s,Runnable r){Button b=new Button(a);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
    void showCatalog(){work(()->{List<ItemCatalog.Item> items=catalog.load();ui(()->browse(items));});}
    private void browse(List<ItemCatalog.Item> items){
        LinearLayout l=column();l.addView(text("Offline catalog • "+items.size()+" models\nSourced size proxies, not photorealistic models. Feed downloads do NOT re-check manufacturer pages. Unknown ≠ estimated."));
        EditText query=new EditText(a);query.setSingleLine(true);query.setHint("Search model, manufacturer or category");l.addView(query);
        ListView list=new ListView(a);l.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        List<ItemCatalog.Item> visible=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);
        Runnable filter=()->{visible.clear();adapter.clear();for(ItemCatalog.Item i:items)if(i.matches(query.getText().toString())){visible.add(i);adapter.add(i.title()+"\n"+i.dimensions()+"\nSource retrieved "+i.retrievedDate);}adapter.notifyDataSetChanged();};
        filter.run();query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int x,int c,int f){}public void onTextChanged(CharSequence s,int x,int before,int count){filter.run();}public void afterTextChanged(Editable e){}});
        l.addView(button("Update from internet JSON feed",this::askFeed));l.addView(button("Import catalog JSON file",this::pickCatalog));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Item catalog").setView(l).setNegativeButton("Close",null).create();
        list.setOnItemClickListener((p,v,pos,id)->{ItemCatalog.Item item=visible.get(pos);dialog.dismiss();details(item);});dialog.show();dialog.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*.86));
    }
    private void details(ItemCatalog.Item item){
        LinearLayout l=column();l.addView(text(item.category+" · "+item.dimensions()+"\nSource page retrieved "+item.retrievedDate+"\n\nQuoted specification:\n"+item.sourceQuote+"\n\nOrientation / exclusions:\n"+item.axisNotes+"\n\n"+item.sourceUrl));
        l.addView(button("Open factual source page",()->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(item.sourceUrl)));}catch(Exception e){error(e);}}));
        ScrollView scroll=new ScrollView(a);scroll.addView(l);
        AlertDialog d=new AlertDialog.Builder(a).setTitle(item.title()).setView(scroll).setNegativeButton("Close",null).setNeutralButton("Manual size…",(q,w)->placeForm(item,true)).setPositiveButton("Place sourced size",(q,w)->placeForm(item,false)).create();d.show();d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(item.complete());
    }
    private ScrollView scroll(View view){ScrollView s=new ScrollView(a);s.addView(view);return s;}
    private EditText field(LinearLayout l,String label,String value){l.addView(text(label));EditText e=new EditText(a);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);e.setText(value);l.addView(e);return e;}
    private static float number(EditText e,float min,float max){float f=Float.parseFloat(e.getText().toString());ItemCatalog.require(Float.isFinite(f)&&f>=min&&f<=max,"Number must be "+min+"–"+max);return f;}
    private void placeForm(ItemCatalog.Item item,boolean manual){
        LinearLayout l=column();l.addView(text(manual?"MANUAL size override for this placement only; manufacturer's facts stay unchanged. Enter metres.":item.dimensions()+"\nUses manufacturer's body / deployed / assembled envelope; no accessories or safety clearance inferred."));
        EditText w=manual?field(l,"Width X (m)",item.width==null?"":item.width.toString()):null;
        EditText h=manual?field(l,"Height Y (m)",item.height==null?"":item.height.toString()):null;
        EditText d=manual?field(l,"Depth Z (m)",item.depth==null?"":item.depth.toString()):null;
        EditText bottom=field(l,"Base above floor (m) — placement, not product size","0");
        l.addView(text("Placed near view centre. Drag in Lighting to move; Type turns 45°. Check actual fit/clearance yourself. Camera/light catalog items are size proxies, not calibrated optical/lighting simulations."));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Place "+item.title()).setView(scroll(l)).setNegativeButton("Cancel",null).setPositiveButton("Place",null).create();dialog.setOnShowListener(q->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{double[] size=manual?new double[]{number(w,.001f,20),number(h,.001f,20),number(d,.001f,20)}:null;float b=number(bottom,0,20);gl.queueEvent(()->{try{renderer.placeCatalog(item,size,b);ui(()->{a.showPlacedObject();a.toast("Placed at its real size. Drag it to move it, Turn to rotate.");});}catch(Exception e){error(e);}});dialog.dismiss();}catch(Exception e){a.toast(e.getMessage());}}));dialog.show();
    }
    private void askFeed(){
        LinearLayout l=column();l.addView(text("Supported source: CineX schema-1 catalog JSON in reigokruus/roomscape on raw.githubusercontent.com. NOT an arbitrary product URL or scraper. Imports are previewed, validated and merged by exact model ID; older facts cannot replace newer ones."));EditText url=new EditText(a);url.setSingleLine(true);url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);url.setText(ItemCatalog.DEFAULT_FEED);l.addView(url);
        new AlertDialog.Builder(a).setTitle("Download catalog feed").setView(l).setNegativeButton("Cancel",null).setPositiveButton("Download preview",(d,w)->{String u=url.getText().toString().trim();a.toast("Downloading; existing offline catalog remains available.");work(()->previewImport(CatalogStore.download(u)));}).show();
    }
    private void pickCatalog(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});a.startActivityForResult(i,IMPORT_CATALOG);}catch(Exception e){error(e);}}
    void importUri(Uri uri){work(()->{try(InputStream in=a.getContentResolver().openInputStream(uri)){ItemCatalog.require(in!=null,"Cannot open selected file");previewImport(ItemCatalog.read(in));}});}
    private void previewImport(String json) throws Exception {
        List<ItemCatalog.Item> incoming=ItemCatalog.parse(json);ItemCatalog.merge(catalog.load(),incoming);
        StringBuilder summary=new StringBuilder("Import/replace "+incoming.size()+" exact model IDs? Other cached models stay. Imported claims are not independently reverified by the app.\n\n");for(ItemCatalog.Item i:incoming){if(summary.length()>3000){summary.append("…");break;}summary.append(i.title()).append(" • ").append(i.complete()?"complete size":"UNKNOWN / partial size").append(" • ").append(i.retrievedDate).append('\n');}
        ui(()->new AlertDialog.Builder(a).setTitle("Validated catalog preview").setMessage(summary).setNegativeButton("Cancel",null).setPositiveButton("Import",(d,w)->work(()->{int n=catalog.importText(json);ui(()->{a.toast("Cached "+n+" source records for offline use");showCatalog();});})).show());
    }
    void showScanned(){gl.queueEvent(()->{try{renderer.prepareScannedItems();List<RoomEdits.Detected> all=new ArrayList<>(renderer.roomEdits.detected);Set<String> hidden=new HashSet<>(renderer.roomEdits.hidden);boolean undo=renderer.roomEdits.canUndo();ui(()->{a.planningModeChanged();scannedList(all,hidden,undo);});}catch(Exception e){error(e);}});}
    private void scannedList(List<RoomEdits.Detected> all,Set<String> hidden,boolean undo){
        String[] labels=new String[all.size()];for(int i=0;i<all.size();i++){RoomEdits.Detected d=all.get(i);labels[i]=(hidden.contains(d.id)?"HIDDEN · ":"Visible · ")+d.title+String.format(Locale.US," · %.0f × %.0f × %.0f cm",d.hu*200,(d.y1-d.y0)*100,d.hv*200);}
        LinearLayout col=column();col.addView(text("Planning shapes only — detected furniture bounds, not perfect segmentation. Hide removes the selected detected shape from this digital plan, NOT the original fused scan or photos. No unseen floor/wall surfaces are reconstructed. Walls/floor cannot be removed here."));
        ListView list=new ListView(a);list.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_list_item_1,labels));col.addView(list,new LinearLayout.LayoutParams(-1,0,1));if(all.isEmpty())col.addView(text("No detected furniture shapes in this scan. Original scan remains intact; capture more detail and try again."));
        AlertDialog d=new AlertDialog.Builder(a).setTitle("Scanned items • "+hidden.size()+" hidden").setView(col).setNegativeButton("Close",null).setNeutralButton("Undo this session",(q,w)->changeHidden(()->renderer.roomEdits.undo())).setPositiveButton("Restore all",(q,w)->changeHidden(()->renderer.roomEdits.restoreAll())).create();
        list.setOnItemClickListener((p,v,pos,id)->{d.dismiss();RoomEdits.Detected item=all.get(pos);gl.queueEvent(()->renderer.focusDetected(item.id));new AlertDialog.Builder(a).setTitle(item.title).setMessage("Selected detected shape highlighted in the planning view. Approximate scanned bounds, not a verified product model.\n\nOriginal scan and photographs will remain unchanged. Use Plan / original scan to compare.").setNegativeButton("Cancel",null).setPositiveButton(hidden.contains(item.id)?"Restore shape":"Hide from plan",(q,w)->changeHidden(()->renderer.roomEdits.hide(item.id,!hidden.contains(item.id)))).show();});
        d.show();d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(undo);d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!hidden.isEmpty());d.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*.82));
    }
    private void changeHidden(Runnable change){gl.queueEvent(()->{try{change.run();renderer.savePlanning();ui(this::showScanned);}catch(Exception e){error(e);}});}
    void editSelectedPlacement(){
        gl.queueEvent(()->{Lighting.Prop p; synchronized(renderer.lighting){int i=renderer.lighting.selected-renderer.lighting.lights.size();p=i>=0&&i<renderer.lighting.props.size()?renderer.lighting.props.get(i):null;}
            if(p==null||p.catalog==null){ui(()->a.toast("Select a catalog size proxy first (tap its label)."));return;}
            double[] size=p.size();float x=p.x,z=p.z,b=p.bottom,yaw=p.yawDeg;ui(()->{
                LinearLayout l=column();l.addView(text(p.label()+"\n"+p.catalog.dimensions()+"\nCurrent W/H/D: "+Arrays.toString(size)+" m\n"+p.catalog.sourceUrl+"\nSource retrieved "+p.catalog.retrievedDate+"\n"+p.catalog.axisNotes));
                EditText xx=field(l,"World X (m)",Float.toString(x)),zz=field(l,"World Z (m)",Float.toString(z)),bb=field(l,"Base above floor (m)",Float.toString(b)),yy=field(l,"Yaw (degrees)",Float.toString(yaw));ScrollView scroll=new ScrollView(a);scroll.addView(l);
                AlertDialog d=new AlertDialog.Builder(a).setTitle("Placed item / provenance").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Save position",null).create();d.setOnShowListener(q->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{float nx=number(xx,-100,100),nz=number(zz,-100,100),nb=number(bb,0,20),ny=number(yy,-36000,36000);gl.queueEvent(()->{try{synchronized(renderer.lighting){ItemCatalog.require(renderer.lighting.props.contains(p),"Placement was deleted");p.x=nx;p.z=nz;p.bottom=nb;p.yawDeg=ny;}renderer.savePlanning();a.lightingSelected();}catch(Exception e){error(e);}});d.dismiss();}catch(Exception e){a.toast(e.getMessage());}}));d.show();
            });
        });
    }
}

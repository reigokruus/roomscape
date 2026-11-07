package com.hackathon.setscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Team-run, on-device demo capture. No backend, analytics, mail intent, scan linkage or automatic sending. */
final class PilotIntakeUi {
    static final int EXPORT_PILOT=6231;
    private final Activity a;
    private final PilotIntakeStore store;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String pendingFormat; // format only, never a saved contact payload
    PilotIntakeUi(Activity a){this.a=a;store=new PilotIntakeStore(a);}
    void close(){pendingFormat=null;io.shutdownNow();}
    private interface Work{void run()throws Exception;}
    private void ui(Runnable r){a.runOnUiThread(()->{if(!a.isDestroyed()&&!a.isFinishing())r.run();});}
    private void work(Work r){if(!io.isShutdown())io.execute(()->{try{r.run();}catch(Exception e){error(e);}});}
    private void error(Exception e){String safe=e instanceof PilotIntake.Validation?e.getMessage():"Pilot action did not complete. Check device storage / encryption key or document access. The notebook was not reset; nothing was sent automatically.";ui(()->show(new AlertDialog.Builder(a).setTitle("Pilot notebook").setMessage(safe).setPositiveButton("OK",null).create()));}
    private void toast(String s){ui(()->Toast.makeText(a,s,Toast.LENGTH_LONG).show());}
    private int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(8),dp(18),dp(8));return l;}
    private TextView text(String s){TextView t=new TextView(a);t.setText(s);t.setPadding(0,dp(6),0,dp(6));return t;}
    private ScrollView scroll(View child){ScrollView s=new ScrollView(a);s.addView(child);return s;}
    private Button button(String name,Runnable action){Button b=new Button(a);b.setText(name);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private void show(AlertDialog d){if(d.getWindow()!=null)d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);d.show();d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private EditText field(LinearLayout l,String label,String initial,int max,boolean email,boolean multiline){
        l.addView(text(label));EditText e=new EditText(a);e.setSaveEnabled(false);e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        e.setInputType(InputType.TYPE_CLASS_TEXT|(email?InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        e.setSingleLine(!multiline);if(multiline)e.setMinLines(2);e.setText(initial);
        if(Build.VERSION.SDK_INT>=26){e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setImeOptions(e.getImeOptions()|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);}l.addView(e);return e;
    }
    void open(){work(()->{PilotIntake.Notebook n=store.load();ui(()->menu(n));});}
    private void menu(PilotIntake.Notebook n){
        LinearLayout l=column();l.addView(text("OPTIONAL • local demo notebook, NOT an online signup.\n\nThe CineX team does not automatically receive entries from this or other installs. Use with an identified demo host, who can deliberately export consented contacts for paid-pilot follow-up. No payment details, fees, purchase commitment or automatic messages.\n\nKeep this device locked. Anyone able to open this app can manage its notebook. Exports are unencrypted copies outside the notebook."));
        l.addView(text(n.host==null?"No demo host identified yet.":"Demo host: "+n.host.name+"\nFollow-up / withdrawal contact: "+n.host.contact));
        AlertDialog d=new AlertDialog.Builder(a).setTitle("Interested in a paid pilot?").setView(scroll(l)).setNegativeButton("Not now",null).create();
        Button add=button("Record an opt-in on this device",()->{d.dismiss();form(n,null);});add.setEnabled(n.host!=null);l.addView(add);
        l.addView(button("Identify / change demo host",()->{d.dismiss();hostForm(n);}));
        l.addView(button("Demo host: local contacts ("+n.activeCount()+" active permissions)",()->{d.dismiss();manage(n);}));show(d);
    }
    private void hostForm(PilotIntake.Notebook n){
        LinearLayout l=column();l.addView(text("The person running the demo must identify the actual follow-up recipient and give a withdrawal contact before collecting details. Do not invent a team contact. This configuration does not send anything or create an account."));
        EditText name=field(l,"Demo host / organization name",n.host==null?"":n.host.name,120,false,false),contact=field(l,"Host email for follow-up / withdrawal",n.host==null?"":n.host.contact,254,true,false);
        AlertDialog d=new AlertDialog.Builder(a).setTitle("Identify demo host").setView(scroll(l)).setNegativeButton("Cancel",null).setPositiveButton("Save host locally",null).create();
        d.setOnShowListener(q->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{PilotIntake.Host h=new PilotIntake.Host(name.getText().toString(),contact.getText().toString());busy(d,true);io.execute(()->{try{store.change(cur->cur.withHost(h));ui(()->{d.dismiss();open();});}catch(Exception e){ui(()->busy(d,false));error(e);}});}catch(Exception e){error(e);}}));show(d);
    }
    private void form(PilotIntake.Notebook notebook,PilotIntake.Lead previous){
        PilotIntake.Host shownHost=notebook.host;LinearLayout l=column();
        l.addView(text("Local-only opt-in for "+shownHost.name+". Address and role are self-reported, not verified. Do not enter card/bank details, passwords or sensitive personal information. Closing this form discards the unsaved draft."));
        EditText name=field(l,"Name (optional)",previous==null?"":previous.name,120,false,false),email=field(l,"Email (required)",previous==null?"":previous.email,254,true,false),company=field(l,"Company (optional)",previous==null?"":previous.company,160,false,false),role=field(l,"Role (optional)",previous==null?"":previous.role,120,false,false);
        l.addView(text("Your involvement in selecting/paying for tools (optional, self-reported)"));Spinner authority=new Spinner(a);authority.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,PilotIntake.AUTHORITY_LABELS));authority.setSelection(previous==null?0:previous.authority.ordinal());l.addView(authority);
        EditText project=field(l,"Next real project / actual use case (required)",previous==null?"":previous.project,1000,false,true),problem=field(l,"What specifically gets in the way today? (required)",previous==null?"":previous.problem,1000,false,true);
        CheckBox consent=new CheckBox(a);consent.setSaveEnabled(false);consent.setChecked(false);consent.setText(PilotIntake.notice(shownHost));l.addView(consent);l.addView(text("Consent wording version: "+PilotIntake.CONSENT_VERSION+"\nTimestamp uses this device's clock. Interest is not a validated willingness-to-pay result, confirmed customer, commitment or payment. No consent = no follow-up contact record."));
        AlertDialog d=new AlertDialog.Builder(a).setTitle(previous==null?"Optional paid-pilot interest":"Edit — renew follow-up permission").setView(scroll(l)).setNegativeButton("Cancel",null).setPositiveButton("Save on this device",null).create();
        d.setOnShowListener(q->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            PilotIntake.require(consent.isChecked(),"Permission must be explicitly checked. Nothing was saved.");
            PilotIntake.Draft draft=new PilotIntake.Draft(name.getText().toString(),email.getText().toString(),role.getText().toString(),company.getText().toString(),project.getText().toString(),problem.getText().toString(),PilotIntake.Authority.values()[authority.getSelectedItemPosition()]);busy(d,true);
            io.execute(()->{try{store.change(cur->{PilotIntake.require(cur.host!=null&&cur.host.same(shownHost),"Demo host changed. Reopen the form and renew permission.");if(previous!=null)unchanged(cur,previous);return cur.optIn(previous==null?null:previous.id,draft,true,System.currentTimeMillis());});ui(()->{d.dismiss();toast("Saved only on this device. Not sent to the CineX team.");open();});}catch(Exception e){ui(()->busy(d,false));error(e);}});
        }catch(Exception e){error(e);}}));show(d);
    }
    private void manage(PilotIntake.Notebook n){
        LinearLayout l=column();l.addView(text("Local demo-host notebook • "+n.activeCount()+" active follow-up permissions\nNot a customer/payment count. No central receipt. Protect physical access to this device. Revocation redacts local contact/project fields; exported copies cannot be recalled by this app."));
        String[] labels=new String[n.leads.size()];for(int i=0;i<labels.length;i++){PilotIntake.Lead lead=n.leads.get(i);labels[i]=lead.active()?((lead.name.isEmpty()?lead.email:lead.name+" • "+lead.email)+"\nInterest only • "+PilotIntake.humanTime(lead.consentedAt)):"REVOKED / personal fields removed • "+lead.id.substring(0,8)+"\n"+PilotIntake.humanTime(lead.revokedAt);}
        ListView list=new ListView(a);list.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_list_item_1,labels));l.addView(list,new LinearLayout.LayoutParams(-1,0,1));if(labels.length==0)l.addView(text("No contacts stored. Scanning/planning does not require this feature."));
        Button json=button("Explicit export: consented contacts JSON",()->exportPrompt("json")),csv=button("Explicit export: consented contacts CSV",()->exportPrompt("csv"));json.setEnabled(n.activeCount()>0);csv.setEnabled(n.activeCount()>0);l.addView(json);l.addView(csv);
        AlertDialog d=new AlertDialog.Builder(a).setTitle("Local pilot contacts").setView(l).setNegativeButton("Close",null).create();list.setOnItemClickListener((p,v,pos,id)->{d.dismiss();details(n,n.leads.get(pos));});show(d);d.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*.85));
    }
    private void details(PilotIntake.Notebook n,PilotIntake.Lead lead){
        LinearLayout l=column();
        if(lead.active())l.addView(text("UNVERIFIED INTEREST — NOT A PURCHASE\n"+lead.name+"\n"+lead.email+"\nCompany: "+lead.company+"\nRole: "+lead.role+"\nSelf-reported involvement: "+PilotIntake.AUTHORITY_LABELS[lead.authority.ordinal()]+"\n\nNext real project: "+lead.project+"\n\nCurrent problem: "+lead.problem));else l.addView(text("Permission revoked. Local contact, company, role, authority, project and problem fields were removed. This is only a minimal event tombstone, not an exportable lead."));
        l.addView(text("Consent: "+PilotIntake.humanTime(lead.consentedAt)+"\nVersion: "+lead.consentVersion+"\n"+lead.consentNotice+(lead.active()?"":"\nRevoked: "+PilotIntake.humanTime(lead.revokedAt))));
        AlertDialog d=new AlertDialog.Builder(a).setTitle(lead.active()?"Local contact":"Revoked record").setView(scroll(l)).setNegativeButton("Close",null).create();
        if(lead.active()){l.addView(button("Edit (requires new unchecked opt-in)",()->{d.dismiss();form(n,lead);}));l.addView(button("Revoke follow-up permission",()->{d.dismiss();confirmChange(lead,false);}));}
        l.addView(button("Delete local record",()->{d.dismiss();confirmChange(lead,true);}));show(d);
    }
    private void confirmChange(PilotIntake.Lead lead,boolean delete){
        String message=delete?"Delete this local record? It will no longer be in this notebook. Existing exported copies are NOT deleted; handle those separately.":"Revoke permission and remove local contact/project fields? This record will immediately be excluded from exports. No automatic message is sent. Existing exports cannot be recalled; the host must stop follow-up and handle those copies separately.";
        show(new AlertDialog.Builder(a).setTitle(delete?"Delete record?":"Revoke permission?").setMessage(message).setNegativeButton("Cancel",null).setPositiveButton(delete?"Delete":"Revoke",(d,w)->work(()->{store.change(cur->{unchanged(cur,lead);return delete?cur.delete(lead.id):cur.revoke(lead.id,System.currentTimeMillis());});toast(delete?"Local record deleted; external copies unchanged.":"Permission revoked; local personal fields removed. External copies unchanged.");open();})).create());
    }
    private static void unchanged(PilotIntake.Notebook n,PilotIntake.Lead previous){PilotIntake.Lead current=n.find(previous.id);PilotIntake.require(current!=null&&current.updatedAt==previous.updatedAt&&current.active()==previous.active(),"This record changed. Reopen the list before editing it.");}
    private void exportPrompt(String format){work(()->{PilotIntake.Notebook n=store.load();PilotIntake.require(n.activeCount()>0,"No currently consented contacts to export.");ui(()->show(new AlertDialog.Builder(a).setTitle("Export personal data deliberately?")
            .setMessage("Export "+n.activeCount()+" currently consented contact(s) as "+format.toUpperCase(Locale.US)+"? Revoked records are excluded. This creates an UNENCRYPTED copy at the location you choose; a cloud document provider may upload it there. The copy is not automatically sent to the CineX team. Protect it, limit access and handle revocation/deletion in exported copies yourself. Room/scan export never includes this notebook.")
            .setNegativeButton("Cancel",null).setPositiveButton("Choose export location",(d,w)->{try{pendingFormat=format;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(format.equals("json")?"application/json":"text/csv");i.putExtra(Intent.EXTRA_TITLE,"CineX-consented-pilot-interest."+format);a.startActivityForResult(i,EXPORT_PILOT);}catch(Exception e){pendingFormat=null;error(e);}}).create()));});}
    void exportResult(boolean accepted,Uri uri){String format=pendingFormat;pendingFormat=null;if(!accepted||uri==null||format==null)return;
        work(()->{PilotIntake.Notebook current=store.load();String payload=format.equals("json")?PilotIntake.exportJson(current,System.currentTimeMillis()):PilotIntake.exportCsv(current);
            try(OutputStream out=a.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(payload.getBytes(StandardCharsets.UTF_8));}toast("Exported only currently consented contacts to your chosen location. No automatic team receipt.");});
    }
    private void busy(AlertDialog d,boolean busy){d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);d.setCancelable(!busy);}
}

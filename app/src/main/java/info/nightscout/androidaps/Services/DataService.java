package info.nightscout.androidaps.Services;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.support.annotation.Nullable;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.Where;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.DanaRHistoryRecord;
import info.nightscout.androidaps.db.Treatment;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventNewBasalProfile;
import info.nightscout.androidaps.events.EventTreatmentChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.DanaR.History.DanaRNSHistorySync;
import info.nightscout.androidaps.plugins.NSProfile.NSProfilePlugin;
import info.nightscout.androidaps.plugins.Objectives.ObjectivesPlugin;
import info.nightscout.androidaps.plugins.Overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.SmsCommunicator.SmsCommunicatorPlugin;
import info.nightscout.androidaps.plugins.SmsCommunicator.events.EventNewSMS;
import info.nightscout.androidaps.plugins.SourceNSClient.SourceNSClientPlugin;
import info.nightscout.androidaps.plugins.SourceXdrip.SourceXdripPlugin;
import info.nightscout.androidaps.receivers.DataReceiver;
import info.nightscout.client.data.NSProfile;
import info.nightscout.client.data.NSSgv;
import info.nightscout.utils.ToastUtils;


public class DataService extends IntentService {
    private static Logger log = LoggerFactory.getLogger(DataService.class);

    boolean xDripEnabled = false;
    boolean nsClientEnabled = true;

    public DataService() {
        super("DataService");
        registerBus();
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        if (Config.logFunctionCalls)
            log.debug("onHandleIntent " + intent);

        if (ConfigBuilderPlugin.getActiveBgSource().getClass().equals(SourceXdripPlugin.class)) {
            xDripEnabled = true;
            nsClientEnabled = false;
        } else if (ConfigBuilderPlugin.getActiveBgSource().getClass().equals(SourceNSClientPlugin.class)) {
            xDripEnabled = false;
            nsClientEnabled = true;
        }

        boolean isNSProfile = ConfigBuilderPlugin.getActiveProfile().getClass().equals(NSProfilePlugin.class);

        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        boolean nsUploadOnly = SP.getBoolean("ns_upload_only", false);

        if (intent != null) {
            final String action = intent.getAction();
            if (Intents.ACTION_NEW_BG_ESTIMATE.equals(action)) {
                if (xDripEnabled) {
                    handleNewDataFromXDrip(intent);
                }
            } else if (Intents.ACTION_NEW_SGV.equals(action)) {
                // always handle SGV if NS-Client is the source
                if (nsClientEnabled) {
                    handleNewDataFromNSClient(intent);
                }
                // Objectives 0
                ObjectivesPlugin.bgIsAvailableInNS = true;
                ObjectivesPlugin.saveProgress();
            } else if (isNSProfile && Intents.ACTION_NEW_PROFILE.equals(action)) {
                // always handle Profile if NSProfile is enabled without looking at nsUploadOnly
                handleNewDataFromNSClient(intent);
            } else if (!nsUploadOnly &&
                    (Intents.ACTION_NEW_TREATMENT.equals(action) ||
                            Intents.ACTION_CHANGED_TREATMENT.equals(action) ||
                            Intents.ACTION_REMOVED_TREATMENT.equals(action) ||
                            Intents.ACTION_NEW_STATUS.equals(action) ||
                            Intents.ACTION_NEW_DEVICESTATUS.equals(action) ||
                            Intents.ACTION_NEW_CAL.equals(action) ||
                            Intents.ACTION_NEW_MBG.equals(action))
                    ) {
                handleNewDataFromNSClient(intent);
            } else if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)) {
                handleNewSMS(intent);
            }
        }
        if (Config.logFunctionCalls)
            log.debug("onHandleIntent exit " + intent);
        DataReceiver.completeWakefulIntent(intent);
    }

/*
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (Config.logFunctionCalls)
            log.debug("onStartCommand");

        return START_STICKY;
    }
*/

    @Override
    public void onDestroy() {
        super.onDestroy();
        MainApp.bus().unregister(this);
    }

    private void registerBus() {
        try {
            MainApp.bus().unregister(this);
        } catch (RuntimeException x) {
            // Ignore
        }
        MainApp.bus().register(this);
    }

    private void handleNewDataFromXDrip(Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        BgReading bgReading = new BgReading();

        bgReading.value = bundle.getDouble(Intents.EXTRA_BG_ESTIMATE);
        bgReading.direction = bundle.getString(Intents.EXTRA_BG_SLOPE_NAME);
        bgReading.battery_level = bundle.getInt(Intents.EXTRA_SENSOR_BATTERY);
        bgReading.timeIndex = bundle.getLong(Intents.EXTRA_TIMESTAMP);
        bgReading.raw = bundle.getDouble(Intents.EXTRA_RAW);

        if (bgReading.timeIndex < new Date().getTime() - Constants.hoursToKeepInDatabase * 60 * 60 * 1000L) {
            if (Config.logIncommingBG)
                log.debug("Ignoring old XDRIPREC BG " + bgReading.toString());
            return;
        }

        if (Config.logIncommingBG)
            log.debug("XDRIPREC BG " + bgReading.toString());

        try {
            MainApp.getDbHelper().getDaoBgReadings().createIfNotExists(bgReading);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        MainApp.bus().post(new EventNewBG());
    }

    private void handleNewDataFromNSClient(Intent intent) {
        Bundle bundles = intent.getExtras();
        if (bundles == null) return;
        if (Config.logIncommingData)
            log.debug("Got intent: " + intent.getAction());


        if (intent.getAction().equals(Intents.ACTION_NEW_STATUS)) {
            if (Config.logIncommingData)
                log.debug("Received status: " + bundles);
            if (bundles.containsKey("nsclientversioncode")) {
                ConfigBuilderPlugin.nightscoutVersionCode = bundles.getInt("nightscoutversioncode"); // for ver 1.2.3 contains 10203
                ConfigBuilderPlugin.nightscoutVersionName = bundles.getString("nightscoutversionname");
                ConfigBuilderPlugin.nsClientVersionCode = bundles.getInt("nsclientversioncode"); // for ver 1.17 contains 117
                ConfigBuilderPlugin.nsClientVersionName = bundles.getString("nsclientversionname");
                log.debug("Got versions: NSClient: " + ConfigBuilderPlugin.nsClientVersionName + " Nightscout: " + ConfigBuilderPlugin.nightscoutVersionName);
                if (ConfigBuilderPlugin.nsClientVersionCode < 118)
                    ToastUtils.showToastInUiThread(MainApp.instance().getApplicationContext(), MainApp.sResources.getString(R.string.unsupportedclientver));
            } else {
                ToastUtils.showToastInUiThread(MainApp.instance().getApplicationContext(), MainApp.sResources.getString(R.string.unsupportedclientver));
            }
            if (bundles.containsKey("status")) {
                try {
                    JSONObject statusJson = new JSONObject(bundles.getString("status"));
                    if (statusJson.has("settings")) {
                        JSONObject settings = statusJson.getJSONObject("settings");
                        if (settings.has("thresholds")) {
                            JSONObject thresholds = settings.getJSONObject("thresholds");
                            if (thresholds.has("bgTargetTop")) {
                                OverviewPlugin.bgTargetHigh = thresholds.getDouble("bgTargetTop");
                            }
                            if (thresholds.has("bgTargetBottom")) {
                                OverviewPlugin.bgTargetLow = thresholds.getDouble("bgTargetBottom");
                            }
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        if (intent.getAction().equals(Intents.ACTION_NEW_DEVICESTATUS)) {
            try {
                if (bundles.containsKey("devicestatus")) {
                    String devicestatusesstring = bundles.getString("devicestatus");
                    JSONObject devicestatusJson = new JSONObject(bundles.getString("devicestatus"));
                    if (devicestatusJson.has("pump")) {
                        // Objectives 0
                        ObjectivesPlugin.pumpStatusIsAvailableInNS = true;
                        ObjectivesPlugin.saveProgress();
                    }
                }
                if (bundles.containsKey("devicestatuses")) {
                    String devicestatusesstring = bundles.getString("devicestatuses");
                    JSONArray jsonArray = new JSONArray(devicestatusesstring);
                    if (jsonArray.length() > 0) {
                        JSONObject devicestatusJson = jsonArray.getJSONObject(0);
                        if (devicestatusJson.has("pump")) {
                            // Objectives 0
                            ObjectivesPlugin.pumpStatusIsAvailableInNS = true;
                            ObjectivesPlugin.saveProgress();
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // Handle profile
        if (intent.getAction().equals(Intents.ACTION_NEW_PROFILE)) {
            try {
                String activeProfile = bundles.getString("activeprofile");
                String profile = bundles.getString("profile");
                NSProfile nsProfile = new NSProfile(new JSONObject(profile), activeProfile);
                MainApp.bus().post(new EventNewBasalProfile(nsProfile));

                PumpInterface pump = MainApp.getConfigBuilder();
                if (pump != null) {
                    SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    if (SP.getBoolean("syncprofiletopump", false)) {
                        if (pump.setNewBasalProfile(nsProfile) == PumpInterface.SUCCESS) {
                            SmsCommunicatorPlugin smsCommunicatorPlugin = (SmsCommunicatorPlugin) MainApp.getSpecificPlugin(SmsCommunicatorPlugin.class);
                            if (smsCommunicatorPlugin != null && smsCommunicatorPlugin.isEnabled(PluginBase.GENERAL)) {
                                smsCommunicatorPlugin.sendNotificationToAllNumbers(MainApp.sResources.getString(R.string.profile_set_ok));
                            }
                        }
                    }
                } else {
                    log.error("No active pump selected");
                }
                if (Config.logIncommingData)
                    log.debug("Received profile: " + activeProfile + " " + profile);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        if (intent.getAction().equals(Intents.ACTION_NEW_TREATMENT)) {
            try {
                if (bundles.containsKey("treatment")) {
                    String trstring = bundles.getString("treatment");
                    handleAddedTreatment(trstring);
                }
                if (bundles.containsKey("treatments")) {
                    String trstring = bundles.getString("treatments");
                    JSONArray jsonArray = new JSONArray(trstring);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject trJson = jsonArray.getJSONObject(i);
                        String trstr = trJson.toString();
                        handleAddedTreatment(trstr);
                    }
                }
                scheduleTreatmentChange();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        if (intent.getAction().equals(Intents.ACTION_CHANGED_TREATMENT)) {
            try {
                if (bundles.containsKey("treatment")) {
                    String trstring = bundles.getString("treatment");
                    handleChangedTreatment(trstring);
                }
                if (bundles.containsKey("treatments")) {
                    String trstring = bundles.getString("treatments");
                    JSONArray jsonArray = new JSONArray(trstring);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject trJson = jsonArray.getJSONObject(i);
                        String trstr = trJson.toString();
                        handleChangedTreatment(trstr);
                    }
                }
                scheduleTreatmentChange();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (intent.getAction().equals(Intents.ACTION_REMOVED_TREATMENT)) {
            try {
                if (bundles.containsKey("treatment")) {
                    String trstring = bundles.getString("treatment");
                    JSONObject trJson = new JSONObject(trstring);
                    String _id = trJson.getString("_id");
                    removeTreatmentFromDb(_id);
                }

                if (bundles.containsKey("treatments")) {
                    String trstring = bundles.getString("treatments");
                    JSONArray jsonArray = new JSONArray(trstring);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject trJson = jsonArray.getJSONObject(i);
                        String _id = trJson.getString("_id");
                        removeTreatmentFromDb(_id);
                    }
                }
                scheduleTreatmentChange();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (intent.getAction().equals(Intents.ACTION_NEW_SGV)) {
            try {
                if (bundles.containsKey("sgv")) {
                    String sgvstring = bundles.getString("sgv");
                    JSONObject sgvJson = new JSONObject(sgvstring);
                    NSSgv nsSgv = new NSSgv(sgvJson);
                    BgReading bgReading = new BgReading(nsSgv);
                    if (bgReading.timeIndex < new Date().getTime() - Constants.hoursToKeepInDatabase * 60 * 60 * 1000l) {
                        if (Config.logIncommingData)
                            log.debug("Ignoring old BG: " + bgReading.toString());
                        return;
                    }
                    MainApp.getDbHelper().getDaoBgReadings().createIfNotExists(bgReading);
                    if (Config.logIncommingData)
                        log.debug("ADD: Stored new BG: " + bgReading.toString());
                }

                if (bundles.containsKey("sgvs")) {
                    String sgvstring = bundles.getString("sgvs");
                    JSONArray jsonArray = new JSONArray(sgvstring);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject sgvJson = jsonArray.getJSONObject(i);
                        NSSgv nsSgv = new NSSgv(sgvJson);
                        BgReading bgReading = new BgReading(nsSgv);
                        if (bgReading.timeIndex < new Date().getTime() - Constants.hoursToKeepInDatabase * 60 * 60 * 1000l) {
                            if (Config.logIncommingData)
                                log.debug("Ignoring old BG: " + bgReading.toString());
                        } else {
                            MainApp.getDbHelper().getDaoBgReadings().createIfNotExists(bgReading);
                            if (Config.logIncommingData)
                                log.debug("ADD: Stored new BG: " + bgReading.toString());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            MainApp.bus().post(new EventNewBG());
        }

        if (intent.getAction().equals(Intents.ACTION_NEW_MBG)) {
            log.error("Not implemented yet"); // TODO implemeng MBGS
        }
    }

    private void handleAddedTreatment(String trstring) throws JSONException, SQLException {
        JSONObject trJson = new JSONObject(trstring);
        handleDanaRHistoryRecords(trJson); // update record _id in history
        if (!trJson.has("insulin") && !trJson.has("carbs")) {
            if (Config.logIncommingData)
                log.debug("ADD: Uninterested treatment: " + trstring);
            return;
        }

        Treatment stored = null;
        String _id = trJson.getString("_id");

        if (trJson.has("timeIndex")) {
            if (Config.logIncommingData)
                log.debug("ADD: timeIndex found: " + trstring);
            stored = findByTimeIndex(trJson.getLong("timeIndex"));
        } else {
            stored = findById(_id);
        }

        if (stored != null) {
            if (Config.logIncommingData)
                log.debug("ADD: Existing treatment: " + trstring);
            if (trJson.has("timeIndex")) {
                stored._id = _id;
                int updated = MainApp.getDbHelper().getDaoTreatments().update(stored);
                if (Config.logIncommingData)
                    log.debug("Records updated: " + updated);
            }
        } else {
            if (Config.logIncommingData)
                log.debug("ADD: New treatment: " + trstring);
            Treatment treatment = new Treatment();
            treatment._id = _id;
            treatment.carbs = trJson.has("carbs") ? trJson.getDouble("carbs") : 0;
            treatment.insulin = trJson.has("insulin") ? trJson.getDouble("insulin") : 0d;
            treatment.created_at = new Date(trJson.getLong("mills"));
            if (trJson.has("eventType")) {
                treatment.mealBolus = true;
                if (trJson.get("eventType").equals("Correction Bolus")) treatment.mealBolus = false;
                if (trJson.get("eventType").equals("Bolus Wizard") && treatment.carbs <= 0)
                    treatment.mealBolus = false;
            }
            treatment.setTimeIndex(treatment.getTimeIndex());
            try {
                MainApp.getDbHelper().getDaoTreatments().createOrUpdate(treatment);
                if (Config.logIncommingData)
                    log.debug("ADD: Stored treatment: " + treatment.log());
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleChangedTreatment(String trstring) throws JSONException, SQLException {
        JSONObject trJson = new JSONObject(trstring);
        handleDanaRHistoryRecords(trJson); // update record _id in history
        if (!trJson.has("insulin") && !trJson.has("carbs")) {
            if (Config.logIncommingData)
                log.debug("CHANGE: Uninterested treatment: " + trstring);
            return;
        }
        String _id = trJson.getString("_id");

        Treatment stored;

        if (trJson.has("timeIndex")) {
            if (Config.logIncommingData)
                log.debug("ADD: timeIndex found: " + trstring);
            stored = findByTimeIndex(trJson.getLong("timeIndex"));
        } else {
            stored = findById(_id);
        }

        if (stored != null) {
            if (Config.logIncommingData)
                log.debug("CHANGE: Removing old: " + trstring);
            removeTreatmentFromDb(_id);
        }

        if (Config.logIncommingData)
            log.debug("CHANGE: Adding new treatment: " + trstring);
        Treatment treatment = new Treatment();
        treatment._id = _id;
        treatment.carbs = trJson.has("carbs") ? trJson.getDouble("carbs") : 0;
        treatment.insulin = trJson.has("insulin") ? trJson.getDouble("insulin") : 0d;
        //treatment.created_at = DateUtil.fromISODateString(trJson.getString("created_at"));
        treatment.created_at = new Date(trJson.getLong("mills"));
        if (trJson.has("eventType")) {
            treatment.mealBolus = true;
            if (trJson.get("eventType").equals("Correction Bolus")) treatment.mealBolus = false;
            if (trJson.get("eventType").equals("Bolus Wizard") && treatment.carbs <= 0)
                treatment.mealBolus = false;
        }
        treatment.setTimeIndex(treatment.getTimeIndex());
        try {
            Dao.CreateOrUpdateStatus status = MainApp.getDbHelper().getDaoTreatments().createOrUpdate(treatment);
            if (Config.logIncommingData)
                log.debug("Records updated: " + status.getNumLinesChanged());
            if (Config.logIncommingData)
                log.debug("CHANGE: Stored treatment: " + treatment.log());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void handleDanaRHistoryRecords(JSONObject trJson) throws JSONException, SQLException {
        if (trJson.has(DanaRNSHistorySync.DANARSIGNATURE)) {
            Dao<DanaRHistoryRecord, String> daoHistoryRecords = MainApp.getDbHelper().getDaoDanaRHistory();
            QueryBuilder<DanaRHistoryRecord, String> queryBuilder = daoHistoryRecords.queryBuilder();
            Where where = queryBuilder.where();
            where.ge("bytes", trJson.get(DanaRNSHistorySync.DANARSIGNATURE));
            PreparedQuery<DanaRHistoryRecord> preparedQuery = queryBuilder.prepare();
            List<DanaRHistoryRecord> list = daoHistoryRecords.query(preparedQuery);
            if (list.size() == 0) {
                // Record does not exists. Ignore
            } else if (list.size() == 1) {
                DanaRHistoryRecord record = list.get(0);
                if (record.get_id() == null || record.get_id() != trJson.getString("_id")) {
                    if (Config.logIncommingData)
                        log.debug("Updating _id in DanaR history database: " + trJson.getString("_id"));
                    record.set_id(trJson.getString("_id"));
                    daoHistoryRecords.update(record);
                } else {
                    // already set
                }
            }
        }
    }

    @Nullable
    public static Treatment findById(String _id) {
        try {
            Dao<Treatment, Long> daoTreatments = MainApp.getDbHelper().getDaoTreatments();
            QueryBuilder<Treatment, Long> queryBuilder = daoTreatments.queryBuilder();
            Where where = queryBuilder.where();
            where.eq("_id", _id);
            queryBuilder.limit(10);
            PreparedQuery<Treatment> preparedQuery = queryBuilder.prepare();
            List<Treatment> trList = daoTreatments.query(preparedQuery);
            if (trList.size() != 1) {
                //log.debug("Treatment findById query size: " + trList.size());
                return null;
            } else {
                //log.debug("Treatment findById found: " + trList.get(0).log());
                return trList.get(0);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Nullable
    public static Treatment findByTimeIndex(Long timeIndex) {
        try {
            QueryBuilder<Treatment, String> qb = null;
            Dao<Treatment, Long> daoTreatments = MainApp.getDbHelper().getDaoTreatments();
            QueryBuilder<Treatment, Long> queryBuilder = daoTreatments.queryBuilder();
            Where where = queryBuilder.where();
            where.eq("timeIndex", timeIndex);
            queryBuilder.limit(10);
            PreparedQuery<Treatment> preparedQuery = queryBuilder.prepare();
            List<Treatment> trList = daoTreatments.query(preparedQuery);
            if (trList.size() != 1) {
                log.debug("Treatment findByTimeIndex query size: " + trList.size());
                return null;
            } else {
                log.debug("Treatment findByTimeIndex found: " + trList.get(0).log());
                return trList.get(0);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void removeTreatmentFromDb(String _id) throws SQLException {
        Treatment stored = findById(_id);
        if (stored != null) {
            log.debug("REMOVE: Existing treatment (removing): " + _id);
            int removed = MainApp.getDbHelper().getDaoTreatments().delete(stored);
            if (Config.logIncommingData)
                log.debug("Records removed: " + removed);
            scheduleTreatmentChange();
        } else {
            log.debug("REMOVE: Not stored treatment (ignoring): " + _id);
        }
    }

    private void handleNewSMS(Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;
        MainApp.bus().post(new EventNewSMS(bundle));
    }

    public void scheduleTreatmentChange() {
        MainApp.bus().post(new EventTreatmentChange());
    }


}

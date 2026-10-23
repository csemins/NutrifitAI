package com.transformsai.nutrifitai.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.remote.AiApiClient;
import com.transformsai.nutrifitai.data.remote.PresenceManager;
import com.transformsai.nutrifitai.data.repo.AnalysisRepository;
import com.transformsai.nutrifitai.data.repo.AssistantRepository;
import com.transformsai.nutrifitai.data.repo.AuthRepository;
import com.transformsai.nutrifitai.data.repo.DiaryRepository;
import com.transformsai.nutrifitai.data.repo.FoodRepository;
import com.transformsai.nutrifitai.data.repo.ImportExportRepository;
import com.transformsai.nutrifitai.data.repo.ProfileRepository;
import com.transformsai.nutrifitai.data.repo.RecipeRepository;
import com.transformsai.nutrifitai.data.repo.ReminderRepository;
import com.transformsai.nutrifitai.data.repo.WaterRepository;
import com.transformsai.nutrifitai.data.repo.WeightRepository;
import com.transformsai.nutrifitai.data.repo.WorkoutRepository;
import com.transformsai.nutrifitai.data.repo.StepsRepository;
import com.transformsai.nutrifitai.data.repo.FastingRepository;
import com.transformsai.nutrifitai.sync.Outbox;
import com.transformsai.nutrifitai.sync.SyncScheduler;
import com.transformsai.nutrifitai.data.remote.AiAccess;
import com.transformsai.nutrifitai.social.ChallengeRepository;
import com.transformsai.nutrifitai.social.ChatRepository;
import com.transformsai.nutrifitai.social.FeedRepository;
import com.transformsai.nutrifitai.social.FriendsRepository;
import com.transformsai.nutrifitai.social.InboxRepository;
import com.transformsai.nutrifitai.social.InboxWorker;
import com.transformsai.nutrifitai.social.ProfileCards;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hand-written dependency graph (service locator) shared by Activities, Workers and Receivers. */
public final class AppGraph {
    public final Context context;
    public final AppDatabase db;
    public final ExecutorService io = Executors.newFixedThreadPool(3);
    public final Handler main = new Handler(Looper.getMainLooper());
    public final Prefs prefs;
    public final Session session;
    public final Today today = new Today();
    public final Outbox outbox;
    public final PresenceManager presence;
    public final AiApiClient ai;
    public final AiAccess aiAccess;

    public final AuthRepository auth;
    public final ProfileRepository profile;
    public final FoodRepository foods;
    public final DiaryRepository diary;
    public final WaterRepository water;
    public final WeightRepository weights;
    public final RecipeRepository recipes;
    public final ReminderRepository reminders;
    public final AnalysisRepository analysis;
    public final AssistantRepository assistant;
    public final ImportExportRepository importExport;
    public final WorkoutRepository workouts;
    public final StepsRepository steps;
    public final FastingRepository fasting;
    public final ProfileCards cards;
    public final FriendsRepository friends;
    public final FeedRepository feed;
    public final ChallengeRepository challenges;
    public final ChatRepository chats;
    public final InboxRepository inbox;

    public AppGraph(Context c) {
        context = c.getApplicationContext();
        db = AppDatabase.build(context);
        prefs = new Prefs(context);
        session = new Session();
        outbox = new Outbox(context, db);
        presence = new PresenceManager(context, prefs);
        ai = new AiApiClient(prefs);
        aiAccess = new AiAccess(ai, prefs);
        auth = new AuthRepository(this);
        profile = new ProfileRepository(this);
        foods = new FoodRepository(this);
        diary = new DiaryRepository(this);
        water = new WaterRepository(this);
        weights = new WeightRepository(this);
        recipes = new RecipeRepository(this);
        reminders = new ReminderRepository(this);
        analysis = new AnalysisRepository(this);
        assistant = new AssistantRepository(this);
        importExport = new ImportExportRepository(this);
        workouts = new WorkoutRepository(this);
        steps = new StepsRepository(this);
        fasting = new FastingRepository(this);
        cards = new ProfileCards(this);
        friends = new FriendsRepository(this);
        feed = new FeedRepository(this);
        challenges = new ChallengeRepository(this);
        chats = new ChatRepository(this);
        inbox = new InboxRepository(this);
    }

    public void start() {
        io.execute(() -> {
            foods.seedCatalogIfNeeded();
            foods.seedUsdaIfNeeded();
        });
        today.refresh();
    }

    public void onForeground() {
        today.refresh();
        String uid = session.uid();
        if (uid != null) {
            SyncScheduler.requestSync(context);
            presence.start(uid);
            aiAccess.refresh();
            inbox.start();
            InboxWorker.schedule(context);
        }
    }

    public void onBackground() {
        presence.stop();
        today.stop();
    }

    public void runIo(Runnable r) {
        io.execute(r);
    }

    public <T> void postResult(Result.Callback<T> cb, Result<T> r) {
        if (cb != null) main.post(() -> cb.onResult(r));
    }
}

# NutriFit AI: presentation and live demo guide

Each required area, where it lives in the code, and a short demo step. Paths are under
`app/src/main/java/com/transformsai/nutrifitai/` unless noted. Version 1.2.4.

## 1. Overview and features

NutriFit AI is a nutrition and home-fitness coach for users in Bangladesh, with a social layer.

| Area | What to show |
|---|---|
| Food logging | Camera (centre of the bar) → photo → AI estimate → edit → confirm. Voice log, barcode, search (Bangladeshi + USDA foods). |
| Diary and targets | Home ring (calories left, daily target), macros, water; Meals tab with planned vs eaten. |
| Mapped walks | Steps › Start a mapped walk: live OpenStreetMap route, distance, pace, speed; Finish → summary with splits and Share. |
| Hydration, weight, steps, fasting | Water quick-add and reminders, weight chart, walk tracker, fasting timer. |
| Training | Train tab → program → camera workout: reps counted by on-device pose tracking, form faults rejected. |
| AI coach | Floating sparkle button on every tab; answers from your own logs, proposes actions you confirm. |
| Social | Social (blue button next to the camera): feed with reactions and comments, chats and friend groups, voice/video calls, live workout challenges with a scoreboard. |
| Profile and settings | Tap your photo (red badge = new notifications): profile card, Notifications, Edit profile (photo, @username, birth date, body with BMI, goal, food), Appearance, Privacy & data. AI access is managed in the Firebase console. |

## 2. Architecture and navigation

| Topic | Implementation |
|---|---|
| Multi-Activity navigation | `ui/launch/LauncherActivity` routes to `ui/auth/WelcomeActivity` → `AuthActivity` → `ui/onboarding/OnboardingActivity` → `ui/main/MainActivity` (5 tab fragments). About 30 activities, all declared in `AndroidManifest.xml` with explicit `exported` values. |
| Explicit intents | `AuthActivity.forMode(context, MODE_SIGN_UP)`, `WorkoutActivity.forChallenge(context, id)`, `ChallengeLobbyActivity.intent(...)`, `ChatActivity.intent(context, chatId)`, `CallActivity.intent(context, room, title, video)`, `CommentsActivity.intent(context, postId)`, `NotificationsActivity.open(context, item)` (from a notification, routes on to the right screen); intent extras carry ids and modes. |
| Implicit intents | Share any post, progress, workout, challenge result or badge as an image: `ACTION_SEND` with a FileProvider `image/png` URI + chooser (`ui/common/ShareCards`). Invite a friend by @username (`SocialFragment.invite`). Share an export: `ACTION_SEND` with FileProvider URIs (`io/ExportService.shareIntent`). "Open with NutriFit AI" for XML backups: `ACTION_VIEW` intent filter on `ExportImportActivity`. App settings: `ACTION_APPLICATION_DETAILS_SETTINGS` (`ui/common/Ui.openAppSettings`). |
| View Binding | `viewBinding true` in `app/build.gradle`; every screen inflates its binding (e.g. `ActivityWelcomeBinding`, `FragmentSocialBinding`, `ItemPostBinding`). |
| Event handling | Click and long-click listeners, chip group selection (`NewChallengeActivity`), ViewPager2 page callbacks (`WelcomeActivity`), text watchers (live username check `ProfileForm.UsernameWatcher`, live BMI, AI portion rescaling in `AnalysisActivity`), long-press reactions (`ReactionPicker`), IME actions (search fields), double-tap guard `Ui.onClick`. |
| Lifecycle | `WorkoutActivity`: camera + pose model released in `onStop`, clip recording stopped, live challenge listener removed in `onDestroy`, late callbacks ignored once destroyed. `FirestoreLive` attaches Firestore listeners only while observed. |
| MVVM | ViewModels + LiveData (`AssistantViewModel`, `HomeViewModel`, `AuthViewModel` with `SavedStateHandle`), repositories in `data/repo` and `social/` (`FriendsRepository`, `FeedRepository`, `ChatRepository`, `ChallengeRepository`, `InboxRepository`, `ProfileCards`). |

Demo: Welcome → "I already have an account" (explicit intent with a mode extra) → sign in → Social → Feed →
Share on a post (image card in the chooser).

## 3. UI and lists

| Topic | Implementation |
|---|---|
| Layouts | ConstraintLayout (`activity_welcome.xml`, `item_welcome_slide.xml`, `fragment_home.xml`, `activity_onboarding.xml`), LinearLayout (most cards and rows), FrameLayout and CoordinatorLayout (`activity_workout.xml`, `activity_main.xml`). |
| RecyclerView + Adapter + ViewHolder | `ui/social/SocialAdapter` (ListAdapter + DiffUtil, four view types: section, post, row, empty state); `ui/social/ChatActivity` (messages, reverse layout, challenge/call cards), `CommentsActivity` (`ConcatAdapter`: the post + its comments), `NotificationsActivity` (swipe to delete), `NewGroupActivity` (friend picker); `ui/assistant/ChatHistorySheet` (grouped chats); `ui/main/DiaryAdapter`; `ui/assistant` chat list; `WelcomeActivity.SlideAdapter` (ViewPager2). |
| Custom views | `ui/widget/LivePoseView` (friend's live skeleton), `BmiScaleView`, `ProfileBadgeView` (avatar + unread badge), `BarChartView` (bars or trend line), `CalorieRingView`, `HydrationGaugeView`, `MuscleMapView`, `PoseOverlayView`. |
| Theming | Colour tokens in `res/values/colors.xml` (light) and `res/values-night/colors.xml` (dark); `App.applyTheme`. |

Demo: Social → Feed (hold Like for reactions, open comments), Chats (message a friend), Friends (requests and friend rows).

## 4. Android components and permissions

| Topic | Implementation | Demo |
|---|---|---|
| System broadcasts | `reminders/SystemEventsReceiver` (registered in `MainActivity.onStart`, removed in `onStop`): airplane mode on/off, battery low, battery saver. Effects: offline notice, sync restarts when back online, workout camera switches to the lite pose model (`PoseModelStore.resolve`). Manifest receivers: `BootReceiver` (restore reminders after reboot) and `TimeChangeReceiver` (time/time zone changes). | Toggle airplane mode with the app open: a snackbar appears; turn it off: "Back online. Syncing your logs." Start a walk (Profile > Walk tracker), go home, turn battery saver on: the walk stops, is saved, and a notification explains why (`health/WalkService` registers the receiver while the walk runs). Settings > System events lists each event and the app's reaction (`reminders/SystemEventLog`). Emulator: `adb shell dumpsys battery unplug` then `adb shell settings put global low_power 1`. |
| Content providers | `FileProvider` (`${applicationId}.fileprovider`) for sharing exports; `io/DownloadsSaver` inserts into `MediaStore.Downloads` via `ContentResolver` (Settings > Privacy & data > Export > Save to Downloads); the photo picker returns `content://` URIs read through `ContentResolver` (`ComposePostActivity`, avatar, meal photos). | Export → Save to Downloads → open the Files app → Downloads/NutriFit AI. |
| Foreground services | `io/ExportService` (`dataSync`): progress notification while it runs, cancellable from the export screen; `health/WalkService` (`health`): live step count in the notification. | Export with the notification shade open. |
| Background work | WorkManager: `sync/SyncWorker` (outbox sync with retries), `ai/AnalysisWorker` (photo analysis survives process death), `health/StepWorker`, `social/InboxWorker` (checks for new notifications about every 15 minutes). | — |
| Permissions | Signup Permissions step (`OnboardingActivity`, `ob_step_permissions.xml`): notifications, camera, microphone, activity + Health Connect, each with Allow / Allowed. In-context re-asking with a settings fallback when blocked (`ui/common/Permissions.Asker`, used by calls, challenge voice and the coach mic); Notifications screen banner when off. Runtime requests with rationale and denial recovery: camera (`CameraActivity`, `WorkoutActivity`: challenges explain they need the camera), microphone (voice log, coach, calls, challenge voice), notifications (export, reminders, workout reminder switch), activity recognition (walks). | Deny the camera once, then open a workout. |

## 5. Networking and Firebase

| Topic | Implementation |
|---|---|
| HTTP | OkHttp to the NutriFit AI Java server (`data/remote/AiApiClient`, `BucketClient`). The server (`server/`) verifies Firebase ID tokens, rate-limits, applies the scope guardrail (`Prompts.GUARD_RULES`, `Contract.validateAssistant`) and proxies the LLM. |
| JSON parsing | Gson: AI results (`ai/AiResultParser`, strict validation), assistant replies (`ai/AssistantReplyParser`), Firestore REST on the server (`FirestoreAccess.parseAllowed`). |
| XML parsing | `XmlPullParser` import of NutriFit AI XML backups with preview and duplicate handling (`data/repo/ImportExportRepository`). |
| Firebase Authentication | Email/password and Google sign-in (`data/repo/AuthRepository`, `ui/auth/AuthActivity`), password reset, re-authentication before account deletion. |
| Cloud Firestore | Private sync of diary data (`sync/SyncEngine`, outbox); social data: `usernames`, `publicProfiles`, `friendRequests`, `users/{uid}/friends`, `users/{uid}/inbox`, `posts` + `comments`, `chats` + `messages`, `challenges` (`social/`); AI access: `aiAccess`, `aiAllowList` (`data/remote/AiAccess`, managed in the Firebase console). Rules in `firebase/firestore.rules`, 35 rules tests in `firebase/rules-tests/`. |
| Realtime Database | Presence (`data/remote/PresenceManager`), live challenge state `live/{challengeId}/{uid}` (`social/ChallengeSession`): reps, faults, points, reactions and a compact pose for the tiles, several times per second; call signalling `rtc/{room}` for the WebRTC calls (`call/CallSession`). |
| Media / pictures | Meal photos and avatars (private), feed photos and workout clips (shared with friends) are stored in the server bucket that replaces Firebase Storage (`BucketClient`, `server/.../Bucket.java`). Clips are recorded with CameraX `VideoCapture` (`ui/train/ClipRecorder`). |

Demo: Social → Challenges → Challenge a friend → 20 squats → Send. Friend accepts → Start → both phones count down
together → scoreboard top-left, friend's tile top-right (stick figure, or video if they share it) → send a 🔥 → Results → Share.

## 6. Layout responsiveness

| Topic | Implementation |
|---|---|
| Rotation | Activities follow the device rotation; state survives through ViewModels and `onSaveInstanceState` (the Social tab, the compose type). `WorkoutActivity` handles rotation itself (`configChanges`) so the camera and workout are not restarted. |
| Screen sizes | `values-sw600dp/dimens.xml` (wider padding, larger ring on tablets); `max_content_width` caps text columns; ConstraintLayout ratios keep artwork square on any screen (`item_welcome_slide.xml`). |
| Insets and edge-to-edge | `ui/common/InsetsUtil` pads for status bar, gesture bar, cutouts and keyboard; docked bottom bar extends under the gesture area. |
| Light / dark | Settings > Appearance; colours are theme tokens, camera screens stay dark (`BaseActivity.alwaysDark`). |

## Before the demo

1. Firebase project `nutrifit-ai-6c41c` set up as in README > Setup (providers, databases, SHA fingerprints, rules deployed).
2. Enable AI for the demo accounts in the Firebase console: `aiAccess/{uid}.allowed = true`, or pre-allow their emails in `aiAllowList`.
3. A live challenge or call needs two signed-in phones (or one phone and one emulator) that are friends. Calls connect
   peer-to-peer; on networks that block that, set a TURN relay (README > Setup > Calls).

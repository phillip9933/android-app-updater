# Show a new-version popup in the host app

The host owns presentation and permissions. The core updater exposes state and
verification operations and contains no Activity, Compose dependency or dialog.
The [compilable platform-dialog example](../examples/UpdatePromptController.kt)
is host source to adapt, not a library UI API. The verifier app compiles it against
the current public API. Localize its strings, map errors to helpful copy and test
accessibility with your app's fonts, screen reader and navigation before shipping.

Initialize one `UpdateManager` from your Application using your app's approved
release configuration. WorkManager must also be able to bootstrap after process
recreation. Keep `stateName` and configuration stable across Activity recreation.
Pass the manager and an Application-owned `CoroutineScope(SupervisorJob() +
Dispatchers.Main.immediate)` to one controller per Activity. Call its `onResume()`
after `super.onResume()` and `onPause()` before `super.onPause()`. Callbacks stop
observing and dismiss the dialog when the Activity leaves the foreground.
The application's download scope survives Activity recreation, but not process
death. A manual download can be retried from Updates; Wi-Fi background downloads
remain the SDK's explicit opt-in WorkManager policy.

Always expose an Updates screen or button that calls `showCurrent()`. A popup is
an invitation, not the only way to install or retry. Automatic prompting uses:

- `available`: the currently discovered release; it alone does not mean a prompt
  should be shown or an APK is ready.
- `offer`: the SDK has not recorded Later/dismissal for this tag.
- `ready`: cached bytes passed verification; installation still needs a new user
  action and `prepareInstall()` revalidation.

The example records `presented_tag` in separate host preferences before showing
an automatic dialog. Recollection, rotation and process recreation therefore do
not reopen the same automatic prompt. Later, back and outside dismissal also call
`dismissOffer()`, which persists the SDK's dismissal for that tag. The Updates
entry remains available even when `offer` is false. A new tag can prompt again.
Use only one automatic-prompt owner in a multi-Activity or Compose app; do not
create a controller or launch dialogs directly during recomposition. A Compose
adaptation should use one lifecycle-bound state collector and an equivalent
persistent presentation key.

Update starts `download()` following the tap. The dialog observes byte progress,
offers cancellation through `cancelDownload()`, and shows an error/retry state.
Later dismisses the invitation; it does not cancel a download already requested.
Cancel download is a separate explicit action. Notes and tags are untrusted plain
text: no HTML, executable Markdown, WebView, clickable automatic links or shell
commands. The example bounds displayed notes and binds the dialog to one tag so
a changed feed cannot silently substitute the selection under a confirmation.

The host manifest explicitly chooses `INTERNET`, `ACCESS_NETWORK_STATE` and
`REQUEST_INSTALL_PACKAGES`, subject to its privacy/distribution policy. Do not
duplicate the SDK's FileProvider. Android 8+ requires per-source installation
permission: Allow installation opens Settings only after the tap. Returning from
Settings never installs automatically. The user must reopen Updates and tap
Install. Denial leaves the current app installed and offers a later retry.

On Install, the foreground controller calls `prepareInstall()` again and launches
only its non-null Intent while still resumed, on the same tag and with installation
permission. This repeats cached-file hash, APK identity and signer checks. Keep
the SDK's scoped read-only URI flags; never substitute a release URL, file URI,
download-manager path or broad URI grant. Android retains final confirmation and
signature enforcement. Leaving the Activity during verification cancels the
pending launch. An installer return or successful `startActivity()` does not prove
installation: check the installed package/version on the next app start. Preserve
application data; never suggest uninstalling to fix a signature mismatch.

## Optional UI module decision

The owner decided on 26 October 2026 to keep UI in each host and share this source example. The core's existing
UI-free contract and offline-only consumers make an automatic UI dependency
inappropriate. The current identified need is a popup integration example; no
second consumer's validated requirements justify a separately versioned UI module.
An optional module remains an unscheduled proposal, not a release commitment.

Before proposing that module, record actual consumer requirements for Views and/or
Compose, themes, translations, screen readers, large text, lifecycle/presentation
deduplication, foreground installation and permission denial/retry. Its acceptance
criteria must include Update/Later, plain-text notes, progress/cancellation,
recreation and tag-change tests, explicit consent and no automatic install. It
must be opt-in and add no UI dependency or manifest permission to core-only users.
There is no planned UI module. Reopening this question requires a new proposal
and an explicit repository-owner decision.

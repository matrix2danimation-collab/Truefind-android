/* truefindapp.com is now a plain website: no web app and no notifications from here (the True Find
 * Android app shows its own). A browser that still has the old web app's notification helper gets this
 * file instead when it checks for an update, and it removes itself at once. */
self.addEventListener("install", () => { self.skipWaiting(); });
self.addEventListener("activate", (event) => {
  event.waitUntil(self.registration.unregister().catch(() => {}));
});

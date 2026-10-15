# Galaxy Store launch checklist

## 1. One-time setup
- [ ] Add the two signing secrets to GitHub (see PEELBOX-SIGNING-KEY-README.txt). Keep the key backed up.
- [ ] Turn on GitHub Pages: repo → Settings → Pages → Source "Deploy from a branch", branch `main`, folder `/docs`.
      Privacy policy URL becomes: https://noctaur-dev.github.io/palette-stickers/privacy.html
- [ ] Replace SUPPORT_EMAIL in docs/index.html and docs/privacy.html (or ask Claude to).
- [ ] Create a Samsung account → https://seller.samsungapps.com → register as a **Private seller**.
- [ ] Request **Commercial Seller** status (needed to sell): your name must match your government ID and the
      bank/PayPal account holder; add tax info.

## 2. In-app item (Seller Portal → app → In App Purchase)
- [ ] Add product: ID `peelbox_unlimited`, Non-consumable, $1.99, then **Activate** it.
- [ ] Add your Samsung account under testers (Seller Portal → Assistance/Testing) so you can test purchases for free.

## 3. Test the paid flow before submitting
- [ ] GitHub → Actions → Build APK → Run workflow → tick "IAP test mode" → download the `-iaptest` APK from the run's Artifacts.
- [ ] Uninstall any earlier store build, install it, make 3 packs, try a 4th → Unlock → confirm the test purchase works,
      then Restore purchase after reinstalling.

## 4. Submit
- [ ] App information: title, short + full description, keywords (store/LISTING.md), 512×512 icon (store/icon-512.png),
      4–8 screenshots (2:1 ratio — send phone screenshots to Claude to crop), category, age rating, support email,
      privacy policy URL.
- [ ] Binary: upload the normal store APK (Actions → latest run → Artifacts → Peelbox-store-apk, **not** the -iaptest one).
      Uses Google Mobile Services: **No**.
- [ ] Country/Region & Price: Free (the unlock is the in-app item). Start with United States, add more later.
- [ ] Data safety: no data collected by Peelbox; Samsung IAP → "Device or other IDs".
- [ ] Submit. Optional: run a Closed Beta first (supports in-app purchases).

## Screenshot ideas (in this order)
1. Library of packs with nice covers
2. A pack full of stickers (some animated)
3. The Peelbox keyboard open in a chat
4. A sticker sent in Messenger with no black background
5. Export / .stickerpack menu

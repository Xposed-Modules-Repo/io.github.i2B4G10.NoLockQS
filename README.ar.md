<div dir="rtl">

<p align="center">
  <b>العربية</b> · <a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a>
</p>

<div align="center">

<img src=".github/assets/icon.png" width="128" alt="أيقونة NoLockQS">

# مشروع NoLockQS 🔐🚫

**الهاتف المقفل يجب أن يبقى مقفلًا.**<br>
يخفي NoLockQS الإعدادات السريعة وقائمة التشغيل على شاشة القفل، ويخبرك حين يحاول أحدهم الوصول إليها. 🕵️

[![أحدث إصدار](https://img.shields.io/badge/release-v2.10-DD0E1C)](https://github.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/releases/latest)
[![Android 15 فأحدث](https://img.shields.io/badge/Android-15%2B-3E5C7B?logo=android&logoColor=white)](#-المتطلبات)
[![اللغات](https://img.shields.io/badge/languages-EN%20%C2%B7%20AR%20%C2%B7%20ZH-3E5C7B)](#-ما-الذي-تحصل-عليه)
[![الترخيص](https://img.shields.io/badge/license-Proprietary-3E5C7B)](LICENSE)
<br>
[![Android بصلاحيات الروت](https://img.shields.io/badge/Rooted-Android-00AF9C?logo=magisk&logoColor=white)](#-المتطلبات)
[![وحدة Xposed](https://img.shields.io/badge/Xposed-Vector%20%C2%B7%20LSPosed-6650A4)](#-التثبيت)
[![مُختبر على Android 17](https://img.shields.io/badge/tested-Android%2015%20%C2%B7%2016%20%C2%B7%2017-3DDC84?logo=android&logoColor=white)](#-أنظمة-rom-أخرى)
[![Pixel](https://img.shields.io/badge/Pixel-ready-4285F4?logo=google&logoColor=white)](#-أنظمة-rom-أخرى)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](app/src/main/java)
[![الخصوصية](https://img.shields.io/badge/privacy-no%20internet%20%C2%B7%20no%20tracking-2E7D32)](#-ما-الذي-تحصل-عليه)

<img src=".github/assets/screenshots/home-dark.png" width="200" alt="الشاشة الرئيسية، الوضع الداكن">&nbsp;
<img src=".github/assets/screenshots/history-dark.png" width="200" alt="المحاولات المحظورة، لكل منها أيقونتها الخاصة">&nbsp;
<img src=".github/assets/screenshots/home-light.png" width="200" alt="الشاشة الرئيسية، الوضع الفاتح">&nbsp;
<img src=".github/assets/screenshots/history-ar.png" width="200" alt="العربية، من اليمين إلى اليسار">

</div>

## 🤔 لماذا

يخطف اللصوص الهاتف المقفل ويتجهون مباشرة إلى لوحة السحب للأسفل: تشغيل وضع الطيران، وإيقاف البيانات، وإطفاء الهاتف، ثم يختفون. يغلق NoLockQS هذه الأبواب ما دام الهاتف مقفلًا، ويترك كل ما عداها على حاله.

## ✨ ما الذي تحصل عليه

| | |
|---|---|
| 🧱 **لا إعدادات سريعة أثناء القفل** | لا مربعات ولا مفاتيح تبديل. وتبقى الإشعارات قابلة للفتح والتوسيع. |
| ⏻ **لا قائمة تشغيل أثناء القفل** | لا يمكن إطفاء الهاتف أو إعادة تشغيله من شاشة القفل. |
| ⚡ **فتح فوري** | يعود كل شيء لحظة فتح القفل، ويُقفل من جديد بالسرعة نفسها. |
| 🔔 **تنبيهات وسجل** | اطّلع على كل محاولة محظورة مع وقتها. ينتظر التنبيه الصامت حتى تفتح *أنت* القفل، فلا يُنبَّه اللص أبدًا. |
| 🕵️ **ومحاولات الفتح الفاشلة أيضًا** | رمز PIN خاطئ 🔢، أو نمط، أو كلمة مرور 🔑، أو بصمة 👆، أو وجه 🙂، ولكل منها أيقونة ملوّنة خاصة. لا يُحفظ إلا الوقت والنوع، ولا يُحفظ أبدًا ما أُدخل. |
| 🫧 **تصميم زجاجي** | بطاقات زجاجية شبه شفافة فوق ألوان ناعمة متوهجة. ✨ |
| 🌓 **فاتح أو داكن أو مثل الجهاز** | بضغطة واحدة، ويُطبَّق فورًا. |
| 🌍 **English · العربية · 简体中文** | عربية كاملة من اليمين إلى اليسار، إضافة إلى الصينية المبسطة. |
| 🩺 **تشخيص بضغطة واحدة** | تقرير جاهز للمشاركة حين يحدث خلل. |
| 🔒 **خصوصية** | لا إذن بالوصول إلى الإنترنت، ولا تتبع. كل شيء يبقى على هاتفك. |

## 📋 المتطلبات

- 📱 هاتف بصلاحيات الروت يعمل بنظام Android 15 أو أحدث (مُختبر على Android 15-17): هاتف Pixel، أو ROM بواجهة نظام (System UI) قريبة من الأصلية (AOSP)
- 🚀 Vector 2.2 أو أحدث

> **❗ مهم**
>
> يحتاج **Android 17** إلى Magisk 31.0 أو أحدث، وإصدار canary من Vector (3112 أو أحدث). لا يحمّل Magisk 30.7 ولا Vector 2.2 المستقر الوحدات على Android 17.

### 🧩 أنظمة ROM أخرى

يعمل NoLockQS على واجهة النظام الأصلية (AOSP)، لذا كلما كان الـ ROM أقرب إلى الأصل، كانت النتيجة أفضل.

| الحالة | أنظمة ROM | ملاحظات |
|:---:|---|---|
| ✅<br>**مُختبر** | ![Pixel](https://img.shields.io/badge/Pixel-4285F4?style=for-the-badge&logo=google&logoColor=white) | هاتف Pixel على Android 17، ومحاكيات Android 15 و16 و17 |
| 🟡<br>**يُفترض أن يعمل** | ![LineageOS](https://img.shields.io/badge/LineageOS-167C80?style=for-the-badge&logo=lineageos&logoColor=white) ![crDroid](https://img.shields.io/badge/crDroid-3DDC84?style=for-the-badge&logo=android&logoColor=white) ![Evolution X](https://img.shields.io/badge/Evolution_X-8E24AA?style=for-the-badge&logo=android&logoColor=white) ![أنظمة AOSP أخرى](https://img.shields.io/badge/Other_AOSP_ROMs-5F6368?style=for-the-badge&logo=android&logoColor=white) | شيفرة واجهة النظام نفسها الموجودة في Android الأصلي، ولم يُختبر بعد على جهاز حقيقي |
| ❌<br>**غير مدعوم** | ![Samsung One UI](https://img.shields.io/badge/Samsung_One_UI-1428A0?style=for-the-badge&logo=samsung&logoColor=white) ![Xiaomi HyperOS / MIUI](https://img.shields.io/badge/Xiaomi_HyperOS_%2F_MIUI-FF6900?style=for-the-badge&logo=xiaomi&logoColor=white) ![OPPO ColorOS](https://img.shields.io/badge/OPPO_ColorOS-2D683D?style=for-the-badge&logo=oppo&logoColor=white) ![OnePlus OxygenOS](https://img.shields.io/badge/OnePlus_OxygenOS-F5010C?style=for-the-badge&logo=oneplus&logoColor=white) ![vivo OriginOS](https://img.shields.io/badge/vivo_OriginOS-415FFF?style=for-the-badge&logo=vivo&logoColor=white) | تستبدل لوحة الإعدادات السريعة بلوحتها الخاصة، والأرجح أن NoLockQS لا يصل إليها |

لا تستخدم Pixel؟ اقفل الهاتف وجرّب السحب للأسفل. إن فُتحت اللوحة، فأرسل **تقرير التشخيص**.

## 🚀 التثبيت

1. نزّل ملف APK من [أحدث إصدار](https://github.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/releases/latest) وثبّته.
2. افتح Vector، وفعّل **NoLockQS**، وحدّد النطاق الذي يطلبه: **واجهة النظام** (System UI) و**إطار النظام** (System Framework).
3. أعد التشغيل. سيعرض التطبيق **الوحدة مفعّلة** وتكون الحماية قيد العمل. وإن ظهرت **النطاق غير مكتمل**، فاضغط على البطاقة لإضافة ما ينقص.
4. اسمح بالإشعارات حين يطلبها التطبيق، لتصلك التنبيهات.

> **💡 نصيحة**
>
> هناك ما لا يعمل؟ افتح التطبيق، واضغط **استكشاف الأخطاء ← تقرير التشخيص**، وأرفقه مع بلاغك.

## 📜 الترخيص

© 2026 Faisal AlDossary. جميع الحقوق محفوظة.

NoLockQS برنامج مملوك: يحق لك تثبيته واستخدامه على أجهزتك الخاصة، لكن لا يحق لك إعادة توزيعه أو تعديله أو إعادة تحزيمه. راجع [الترخيص](LICENSE) للاطلاع على الشروط كاملة.

<div align="center">

### ❤️ يمكنك التعبير عن محبتك على [Patreon](https://www.patreon.com/c/Dossary) أو [Ko-fi](https://ko-fi.com/Q8G429G7HX)!

[![ادعمني على Patreon](https://img.shields.io/badge/Support_me_on_Patreon-FF424D?style=for-the-badge&logo=patreon&logoColor=white)](https://www.patreon.com/c/Dossary)
[![ادعمني على Ko-fi](https://img.shields.io/badge/Support_me_on_Ko--fi-72A4F2?style=for-the-badge&logo=kofi&logoColor=white)](https://ko-fi.com/Q8G429G7HX)

<a href="https://github.com/2B-4G10"><img src="app/src/main/res/drawable-nodpi/developer_logo.png" width="64" alt="2B-4G10"></a>

**صُنع في السعودية 🇸🇦**

</div>

</div>

# التوقيع (مفتاح الإصدار الثابت)

> **معطّل لحد ما حمو يقرر.** ملف التوليد موجود في `docs/pending/generate-keystore.yml.DISABLED` ومش شغال كـ workflow. عشان يتفعّل لازم تنقله لـ `.github/workflows/` بقرارك، وأي artifact بيتولّد على ريبو عام ينفع ينزّله أي حد عنده حساب GitHub لحد ما تمسحه.

**الحالة: الكود جاهز، لكن المفتاح لسه ماتولّدش.** تشغيل الخطوات تحت محتاج منك إنت بإيدك (ماتعملش أي حاجة بالنيابة عنك).

## ليه الخطوات دي بالشكل ده (حقايق من وثائق GitHub)

- الـ workflow بالـ token الافتراضي **مش بيقدر يكتب Secrets**. إضافة Secret بتتم بإيدك من إعدادات الريبو.
- ريبو عام = أي حد عنده حساب GitHub يقدر ينزّل الـ artifacts. فملف المفتاح محمي بالباسورد بتاعك بس، ولازم يكون طويل وعشوائي (16 حرف على الأقل، الأفضل 24+).
- المفتاح مابينزلش في الريبو ولا في الشات. وماتفقدوش: لو ضاع، التحديثات فوق النسخ المثبتة مش هتشتغل.

## الخطوات (من الموبايل، من متصفح GitHub، ممكن تحتاج «موقع سطح المكتب»)

1. الريبو > **Settings** > **Secrets and variables** > **Actions** > **New repository secret**.
2. اعمل Secret اسمه `SIGNING_KEYSTORE_PASSWORD` وقيمته باسورد طويل وعشوائي إنت اخترته (احفظه في مدير باسوردات عندك). ماتكتبوش في الشات.
3. **Actions** > **Generate signing keystore (run once)** > **Run workflow**، واكتب `YES` في الخانة.
4. لما يخلص، افتح الـ run ونزّل الـ artifact `signing-keystore-DELETE-AFTER-DOWNLOAD`. احفظ الملفات في مكان آمن عندك.
5. افتح `SIGNING_KEYSTORE_BASE64.txt`، انسخ النص كله، واعمل Secret جديد اسمه `SIGNING_KEYSTORE_BASE64` والصق النص.
6. اعمل Secret تالت `SIGNING_KEY_ALIAS` وقيمته `instinct-lite`.
7. امسح الـ artifact من صفحة الـ run (سلة المهملات)، عشان ماينفضلش متاح.
8. **Actions** > **Build APK** > **Run workflow** > `build_type = release`. الـ workflow بيفشل برسالة واضحة لو أي Secret ناقص، ومابيوقّعش أبدًا بمفتاح debug.
9. قارن بصمة الشهادة في `signature.txt` (في artifact البناء) مع `certificate-fingerprint.txt` اللي حفظتهم.

## ملاحظات

- البناء العادي (`debug`) مابيستخدمش أي Secret.
- تشغيل خطوة 3 مرة تانية بيولّد مفتاح **مختلف**. اتعمل مرة واحدة بس.
- نسخة debug ونسخة release بمفاتيح مختلفة: مش بتتحدّث فوق بعض.

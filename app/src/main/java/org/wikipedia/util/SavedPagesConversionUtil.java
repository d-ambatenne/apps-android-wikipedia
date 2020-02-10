package org.wikipedia.util;

import android.annotation.SuppressLint;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.apache.commons.lang3.StringEscapeUtils;
import org.wikipedia.WikipediaApp;
import org.wikipedia.readinglist.database.ReadingList;
import org.wikipedia.readinglist.database.ReadingListDbHelper;
import org.wikipedia.readinglist.database.ReadingListPage;
import org.wikipedia.settings.Prefs;
import org.wikipedia.util.log.L;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okio.ByteString;

import static org.wikipedia.dataclient.RestService.REST_API_PREFIX;
import static org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory.CACHE_DIR_NAME;

public final class SavedPagesConversionUtil {
    private static final String LEAD_SECTION_ENDPOINT = "/page/mobile-sections-lead/";
    private static final String REMAINING_SECTIONS_ENDPOINT = "/page/mobile-sections-remaining/";
    public static final String CONVERTED_FILES_DIRECTORY_NAME = "converted-files";
    private static List<SavedReadingListPage> PAGES_TO_CONVERT = new ArrayList<>();

    @SuppressLint({"SetJavaScriptEnabled", "CheckResult"})
    public static void runOneTimeSavedPagesConversion() {
        List<ReadingList> allReadingLists = ReadingListDbHelper.instance().getAllLists();
        if (allReadingLists.isEmpty()) {
            Prefs.setOfflinePcsToMobileHtmlConversionComplete(true);
            return;
        }

        for (ReadingList readingList : allReadingLists) {
            for (ReadingListPage page : readingList.pages()) {
                if (page.offline()) {
                    String leadSectionUrl = page.wiki().url() + REST_API_PREFIX + LEAD_SECTION_ENDPOINT + StringUtil.fromHtml(page.apiTitle());
                    String remainingSectionsUrl = page.wiki().url() + REST_API_PREFIX + REMAINING_SECTIONS_ENDPOINT + StringUtil.fromHtml(page.apiTitle());
                    PAGES_TO_CONVERT.add(new SavedReadingListPage(StringUtil.fromHtml(page.apiTitle()).toString(), leadSectionUrl, remainingSectionsUrl));
                }
            }
        }

        if (PAGES_TO_CONVERT.isEmpty()) {
            Prefs.setOfflinePcsToMobileHtmlConversionComplete(true);
            return;
        }

        Completable.fromAction(() -> recordJSONFileNames(PAGES_TO_CONVERT)).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> setUpWebViewForConversion(), L::e);

    }

    private static void recordJSONFileNames(List<SavedReadingListPage> savedReadingListPages) {
        for (SavedReadingListPage savedReadingListPage : savedReadingListPages) {
            savedReadingListPage.setLeadSectionJSON(ByteString.encodeUtf8(savedReadingListPage.getLeadSectionUrl()).md5().hex() + ".1");
            savedReadingListPage.setRemainingSectionsJSON(ByteString.encodeUtf8(savedReadingListPage.getRemainingSectionsUrl()).md5().hex() + ".1");
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void setUpWebViewForConversion() {
        WebView dummyWebviewForConversion = new WebView(WikipediaApp.getInstance().getApplicationContext());
        dummyWebviewForConversion.getSettings().setJavaScriptEnabled(true);
        dummyWebviewForConversion.getSettings().setAllowUniversalAccessFromFileURLs(true);

        try {
            String html = FileUtil.readFile(WikipediaApp.getInstance().getAssets().open("pcs-html-converter/index.html"));

            dummyWebviewForConversion.loadDataWithBaseURL("", html, "text/html", "utf-8", "");

            dummyWebviewForConversion.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    File file = new File(WikipediaApp.getInstance().getFilesDir(), CONVERTED_FILES_DIRECTORY_NAME);
                    if (!file.exists()) {
                        file.mkdirs();
                    }
                    convertToMobileHtml(dummyWebviewForConversion);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void convertToMobileHtml(WebView dummyWebviewForConversion) {
        AtomicInteger fileCount = new AtomicInteger();

        for (SavedReadingListPage savedReadingListPage : PAGES_TO_CONVERT) {
            File offlineCacheDirectory = new File(WikipediaApp.getInstance().getFilesDir(), CACHE_DIR_NAME);

            File leadJSONFile = new File(offlineCacheDirectory, savedReadingListPage.getLeadSectionJSON());
            File remJSONFile = new File(offlineCacheDirectory, savedReadingListPage.getRemainingSectionsJSON());

            String leadJSON = null;
            String remainingSectionsJSON = null;
            try {
                leadJSON = FileUtil.readFile(new FileInputStream(leadJSONFile));
                remainingSectionsJSON = FileUtil.readFile(new FileInputStream(remJSONFile));
            } catch (IOException e) {
                e.printStackTrace();
            }

            dummyWebviewForConversion.evaluateJavascript("PCSHTMLConverter.convertMobileSectionsJSONToMobileHTML(" + leadJSON + "," + remainingSectionsJSON + ")",
                    value -> {
                        storeConvertedFile(value, savedReadingListPage.title);
                        if (fileCount.incrementAndGet() == PAGES_TO_CONVERT.size()) {
                            crossCheckAndComplete();
                        }
                    });
        }
    }

    @SuppressLint("CheckResult")
    private static void storeConvertedFile(String convertedString, String fileName) {
        Completable.fromAction(() -> FileUtil.writeToFileInDirectory(StringEscapeUtils.unescapeJava(convertedString), WikipediaApp.getInstance().getFilesDir() + "/" + CONVERTED_FILES_DIRECTORY_NAME, fileName))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                }, L::e);
    }

    private static void crossCheckAndComplete() {
        List<String> fileNames = new ArrayList<>();
        boolean conversionComplete = true;
        File convertedCacheDirectory = new File(WikipediaApp.getInstance().getFilesDir(), CONVERTED_FILES_DIRECTORY_NAME);
        if (convertedCacheDirectory.exists()) {
            File[] files = convertedCacheDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    fileNames.add(file.getName());
                }
            }
        }
        for (SavedReadingListPage savedReadingListPage : PAGES_TO_CONVERT) {
            if (!fileNames.contains(savedReadingListPage.title)) {
                conversionComplete = false;
            }
        }
        Prefs.setOfflinePcsToMobileHtmlConversionComplete(conversionComplete);
        deleteOldCacheFilesForSavedPages(conversionComplete);
    }

    private static void deleteOldCacheFilesForSavedPages(boolean conversionComplete) {
        if (!conversionComplete) {
            return;
        }
        for (SavedReadingListPage savedReadingListPage : PAGES_TO_CONVERT) {
            FileUtil.deleteRecursively(new File(WikipediaApp.getInstance().getFilesDir() + "/" + CACHE_DIR_NAME, savedReadingListPage.getLeadSectionJSON()));
            FileUtil.deleteRecursively(new File(WikipediaApp.getInstance().getFilesDir() + "/" + CACHE_DIR_NAME, savedReadingListPage.getLeadSectionJSON().substring(0, savedReadingListPage.getLeadSectionJSON().length() - 1) + "0"));
            FileUtil.deleteRecursively(new File(WikipediaApp.getInstance().getFilesDir() + "/" + CACHE_DIR_NAME, savedReadingListPage.getRemainingSectionsJSON()));
            FileUtil.deleteRecursively(new File(WikipediaApp.getInstance().getFilesDir() + "/" + CACHE_DIR_NAME, savedReadingListPage.getRemainingSectionsJSON().substring(0, savedReadingListPage.getRemainingSectionsJSON().length() - 1) + "0"));
        }
    }

    private static class SavedReadingListPage {
        String title;

        String getLeadSectionUrl() {
            return leadSectionUrl;
        }

        String getRemainingSectionsUrl() {
            return remainingSectionsUrl;
        }

        String leadSectionUrl;
        String remainingSectionsUrl;
        String leadSectionJSON;
        String remainingSectionsJSON;

        SavedReadingListPage(String title, String leadSectionUrl, String remainingSectionsUrl) {
            this.title = title;
            this.leadSectionUrl = leadSectionUrl;
            this.remainingSectionsUrl = remainingSectionsUrl;
        }

        String getLeadSectionJSON() {
            return leadSectionJSON;
        }

        void setLeadSectionJSON(String leadSectionJSON) {
            this.leadSectionJSON = leadSectionJSON;
        }

        String getRemainingSectionsJSON() {
            return remainingSectionsJSON;
        }

        void setRemainingSectionsJSON(String remainingSectionsJSON) {
            this.remainingSectionsJSON = remainingSectionsJSON;
        }
    }

    private SavedPagesConversionUtil() {
    }
}

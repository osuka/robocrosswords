/**
 * This file is part of Words With Crosses.
 *
 * Copyright (C) 2009-2010 Robert Cooper
 * Copyright (C) 2013 Adam Rosenfield
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.adamrosenfield.wordswithcrosses.versions;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.net.Uri;

import com.adamrosenfield.wordswithcrosses.WordsWithCrossesApplication;
import com.adamrosenfield.wordswithcrosses.net.AbstractDownloader;
import com.adamrosenfield.wordswithcrosses.net.HTTPException;

@TargetApi(9)
public class GingerbreadUtil extends DefaultUtil {

    private static class DownloadingFile
    {
        public boolean completed = false;
        public boolean succeeded = false;
        public int status = -1;
    }

    private static ConcurrentMap<Long, DownloadingFile> waitingDownloads = new ConcurrentSkipListMap<Long, DownloadingFile>();

    private static Map<Long, DownloadingFile> completedDownloads = new HashMap<Long, DownloadingFile>();

    @Override
    public void downloadFile(URL url, Map<String, String> headers, File destination, boolean notification,
        String title) throws IOException {
        // The DownloadManager can sometimes be buggy on some devices
        // (http://code.google.com/p/android/issues/detail?id=18462).  So
        // don't use it if requested.
        //
        // Also, pre-ICS download managers don't support HTTPS
        if (!prefs.getBoolean("useDownloadManager", true) ||
            "https".equals(url.getProtocol()) && android.os.Build.VERSION.SDK_INT < 15) {
            super.downloadFile(url, headers, destination, notification, title);
            return;
        }

        DownloadManager mgr = (DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);

        Request request = new Request(Uri.parse(url.toString()));
        File tempFile = new File(WordsWithCrossesApplication.TEMP_DIR, destination.getName());

        request.setDestinationUri(Uri.fromFile(tempFile));

        for (Entry<String, String> entry : headers.entrySet()) {
            request.addRequestHeader(entry.getKey(), entry.getValue());
        }

        request.setMimeType("application/x-crossword");

        setNotificationVisibility(request, notification);

        request.setTitle(title);
        long id = mgr.enqueue(request);
        Long idObj = id;

        String scrubbedUrl = AbstractDownloader.scrubUrl(url);
        LOG.info("Downloading " + scrubbedUrl + " ==> " + tempFile + " (id=" + id + ")");

        // If the request completed really fast, we're done
        DownloadingFile downloadingFile;
        boolean completed = false;
        boolean succeeded = false;
        int status = -1;
        synchronized (completedDownloads) {
            downloadingFile = completedDownloads.remove(idObj);
            if (downloadingFile != null) {
                completed = true;
                succeeded = downloadingFile.succeeded;
                status = downloadingFile.status;
            } else {
                downloadingFile = new DownloadingFile();
                waitingDownloads.put(idObj, downloadingFile);
            }
        }

        // Wait for the request to complete, if it hasn't completed already
        if (!completed) {
            try {
                synchronized (downloadingFile) {
                    if (!downloadingFile.completed) {
                        downloadingFile.wait();
                    }
                    succeeded = downloadingFile.succeeded;
                    status = downloadingFile.status;
                }
            } catch (InterruptedException e) {
                LOG.warning("Download interrupted: " + scrubbedUrl);
                throw new IOException("Download interrupted");
            }
        }

        LOG.info("Download " + (succeeded ? "succeeded" : "failed") + ": " + scrubbedUrl);

        if (succeeded) {
            if (!destination.equals(tempFile) && !tempFile.renameTo(destination)) {
                LOG.warning("Failed to rename " + tempFile + " to " + destination);
                throw new IOException("Failed to rename " + tempFile + " to " + destination);
            }
        } else {
            throw new HTTPException(status);
        }
    }

    @Override
    public void onFileDownloaded(long id, boolean succeeded, int status) {
        Long idObj = id;
        synchronized (completedDownloads) {
            DownloadingFile downloadingFile = waitingDownloads.remove(idObj);
            if (downloadingFile != null) {
                synchronized (downloadingFile) {
                    downloadingFile.completed = true;
                    downloadingFile.succeeded = succeeded;
                    downloadingFile.status = status;
                    downloadingFile.notifyAll();
                }
            } else {
                LOG.info("No thread is waiting on download for id=" + id);
                downloadingFile = new DownloadingFile();
                downloadingFile.completed = true;
                downloadingFile.succeeded = succeeded;
                downloadingFile.status = status;
                completedDownloads.put(idObj, downloadingFile);
            }
        }
    }

    protected void setNotificationVisibility(Request reqest, boolean notification) {
    }
}

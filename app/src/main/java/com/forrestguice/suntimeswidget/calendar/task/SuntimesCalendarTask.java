/**
    Copyright (C) 2018-2020 Forrest Guice
    This file is part of SuntimesCalendars.

    SuntimesCalendars is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    SuntimesCalendars is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with SuntimesCalendars.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.forrestguice.suntimeswidget.calendar.task;

import android.annotation.TargetApi;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarAdapter;
import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarDescriptor;
import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarFactory;
import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarSettings;
import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarSyncAdapter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.TreeSet;

@SuppressWarnings("Convert2Diamond")
public class SuntimesCalendarTask extends SuntimesCalendarTaskBase
{
    public static final String TAG = "SuntimesCalendarTask";

    public SuntimesCalendarTask(Context context)
    {
        super(context);
        contextRef = new WeakReference<Context>(context);
        adapter = new SuntimesCalendarAdapter(context.getContentResolver(), SuntimesCalendarDescriptor.getCalendars(context));
        calendarWindow0 = SuntimesCalendarSettings.loadPrefCalendarWindow0(context);
        calendarWindow1 = SuntimesCalendarSettings.loadPrefCalendarWindow1(context);
    }

    private long[] getWindow()
    {
        Calendar startDate = Calendar.getInstance();
        Calendar endDate = Calendar.getInstance();
        Calendar now = Calendar.getInstance();

        startDate.setTimeInMillis(now.getTimeInMillis() - calendarWindow0);
        startDate.set(Calendar.MONTH, 0);            // round down to start of year
        startDate.set(Calendar.DAY_OF_MONTH, 0);
        startDate.set(Calendar.HOUR_OF_DAY, 0);
        startDate.set(Calendar.MINUTE, 0);
        startDate.set(Calendar.SECOND, 0);
        startDate.set(Calendar.MILLISECOND, 0);

        endDate.setTimeInMillis(now.getTimeInMillis() + calendarWindow1);
        endDate.add(Calendar.YEAR, 1);       // round up to end of year
        endDate.set(Calendar.MONTH, 0);
        endDate.set(Calendar.DAY_OF_MONTH, 0);
        endDate.set(Calendar.HOUR_OF_DAY, 0);
        endDate.set(Calendar.MINUTE, 0);
        endDate.set(Calendar.SECOND, 0);
        endDate.set(Calendar.MILLISECOND, 0);

        return new long[] { startDate.getTimeInMillis(), endDate.getTimeInMillis() };
    }

    @Override
    protected Boolean doInBackground(SuntimesCalendarTaskItem... items)
    {
        if (Build.VERSION.SDK_INT < 14) {
            return false;
        }

        if (items.length > 0) {
            setItems(items);
        }

        if (flag_clear && !isCancelled())
        {
            Context context = contextRef.get();
            String[] calendars = SuntimesCalendarDescriptor.getCalendars(context);

            SuntimesCalendarTaskProgress progress0 = new SuntimesCalendarTaskProgress(0, calendars.length, notificationMsgClearing);
            publishProgress(progress0);

            int c = 0;
            for (String calendar : calendars)
            {
                SuntimesCalendarSettings.clearNotes(context, calendar);
                long calendarID = adapter.queryCalendarID(calendar);
                if (calendarID != -1)
                {
                    SuntimesCalendarDescriptor calendarDesc = SuntimesCalendarDescriptor.getDescriptor(context, calendar);
                    progress0.setProgress(c, progress0.getCount(), calendarDesc.calendarTitle());

                    removeCalendarReminders(calendarID, progress0);
                    adapter.removeCalendar(calendarID);
                }
                c++;
            }
            adapter.removeCalendars();
        }

        long[] window = getWindow();
        boolean hasLocation = initLocation();
        boolean retValue = true;

        publishProgress(new SuntimesCalendarTaskProgress(1, 1000, notificationMsgUpdating));
        try {
            int c = 0;
            int n = taskItems.size();
            TreeSet<String> calendarSet = new TreeSet<>(taskItems.keySet());
            SuntimesCalendarFactory factory = new SuntimesCalendarFactory();
            for (String calendarName : calendarSet)
            {
                SuntimesCalendarTaskItem item = taskItems.get(calendarName);
                SuntimesCalendarDescriptor descriptor = SuntimesCalendarDescriptor.getDescriptor(contextRef.get(), calendarName);
                SuntimesCalendar calendar = factory.createCalendar(contextRef.get(), descriptor);
                int action = item.getAction();
                switch (action)
                {
                    case SuntimesCalendarTaskItem.ACTION_DELETE:
                        SuntimesCalendarTaskProgress progress = new SuntimesCalendarTaskProgress(0, 1000, notificationMsgClearing + "\n" + calendar.calendarTitle());
                        publishProgress(null, progress);
                        removeCalendarReminders(calendarName, progress);
                        retValue = retValue && adapter.removeCalendar(calendarName);
                        SuntimesCalendarSettings.clearNotes(contextRef.get(), calendarName);
                        break;

                    case SuntimesCalendarTaskItem.ACTION_REMINDERS_DELETE:
                    case SuntimesCalendarTaskItem.ACTION_REMINDERS_UPDATE:
                        publishProgress(null, new SuntimesCalendarTaskProgress(1, 1000, notificationMsgReminderUpdating));
                        // no-break; fall through to next case

                    case SuntimesCalendarTaskItem.ACTION_UPDATE:
                    default:
                        if (calendar != null)
                        {
                            switch (action)
                            {
                                case SuntimesCalendarTaskItem.ACTION_REMINDERS_DELETE:
                                    //Log.d("DEBUG", "ACTION_REMINDERS_DELETE");
                                    retValue = retValue && removeCalendarReminders(calendar, new SuntimesCalendarTaskProgress(c, n, calendar.calendarTitle() + "\n" + notificationMsgReminders));
                                    break;

                                case SuntimesCalendarTaskItem.ACTION_REMINDERS_UPDATE:
                                    //Log.d("DEBUG", "ACTION_REMINDERS_UPDATE");
                                    retValue = retValue && updateCalendarReminders(calendar, new SuntimesCalendarTaskProgress(c, n, calendar.calendarTitle() + "\n" + notificationMsgReminders));
                                    break;

                                default:
                                    retValue = retValue && hasLocation && initCalendar(calendar, window, new SuntimesCalendarTaskProgress(c, n, calendar.calendarTitle()));
                                    break;
                            }
                            if (!retValue) {
                                String calendarError = calendar.lastError();
                                if (calendarError != null && !calendarError.isEmpty()) {
                                    lastError = calendar.lastError();
                                }
                            }
                        } else {
                            lastError = "Unrecognized calendar " + calendarName;
                            Log.w(TAG, lastError);
                            if (taskItems.size() == 1 || c == (n-1)) {
                                return false;
                            }
                        }
                        break;
                }
                c++;
            }

        } catch (SecurityException e) {
            lastError = "Unable to access provider! " + e;
            Log.e(TAG, lastError);
            return false;
        }

        return retValue;
    }

    /**
     * initCalendar
     */
    private boolean initCalendar(@NonNull SuntimesCalendar calendar, @NonNull long[] window, @NonNull SuntimesCalendarTaskProgress progress0) throws SecurityException
    {
        if (window.length != 2) {
            Log.e(TAG, "initCalendar: invalid window with length " + window.length);
            return false;
        }

        boolean retValue = true;
        long calendarID = adapter.queryCalendarID(calendar.calendarName());
        if (calendarID != -1) {
            retValue = (adapter.removeCalendarEventsBefore(calendarID, window[0]) > 0);
        }

        long bench_start = System.nanoTime();
        retValue = retValue && calendar.initCalendar(new SuntimesCalendarSettings(), adapter, this, progress0, window);
        long bench_end = System.nanoTime();
        Log.i(TAG, "initCalendar (" + calendar + ") in " + ((bench_end - bench_start) / 1000000.0) + " ms");

        return retValue;
    }

    /**
     * updateCalendarReminders
     */
    @TargetApi(14)
    private boolean updateCalendarReminders(@NonNull SuntimesCalendar calendar, @NonNull SuntimesCalendarTaskProgress progress0)
    {
        removeCalendarReminders(calendar.calendarName(), progress0);
        return createCalendarReminders(contextRef.get(), calendar.calendarName(), progress0);
    }

    /**
     * createCalendarReminders
     */
    @TargetApi(14)
    public boolean createCalendarReminders(Context context, String calendar, @NonNull SuntimesCalendarTaskProgress progress0)
    {
        boolean retValue = true;
        int count = SuntimesCalendarSettings.loadPrefCalendarReminderCount(context, calendar);
        SuntimesCalendarTaskProgress progress1 = new SuntimesCalendarTaskProgress(-1, count, progress0.getMessage());
        for (int i=0; i<count; i++)
        {
            int minutes = SuntimesCalendarSettings.loadPrefCalendarReminderMinutes(context, calendar, i);
            int method = SuntimesCalendarSettings.loadPrefCalendarReminderMethod(context, calendar, i);
            if (method != -1) {
                retValue = retValue && createCalendarReminders(calendar, minutes, method, progress0, progress1);
            }
        }
        return retValue;
    }

    @TargetApi(14)
    public boolean createCalendarReminders(String calendar, int minutes, int method, @NonNull SuntimesCalendarTaskProgress progress0, @NonNull SuntimesCalendarTaskProgress progress1)
    {
        long calendarID = adapter.queryCalendarID(calendar);
        if (calendarID != -1) {
            return createCalendarReminders(calendarID, minutes, method, progress0, progress1);
        } else {
            Log.w(TAG, "createCalendarReminders: calendar not found! " + calendar);
            return false;
        }
    }
    @TargetApi(14)
    public boolean createCalendarReminders(long calendarID, int minutes, int method, @NonNull SuntimesCalendarTaskProgress progress0, @NonNull SuntimesCalendarTaskProgress progress1)
    {
        ArrayList<ContentValues> reminderValues = new ArrayList<>();
        Cursor cursor = adapter.queryCalendarEvents(calendarID);

        int i = progress1.itemNum();
        int c = Math.max(i, 0);
        int n = ((i == -1) ? progress1.getCount() * cursor.getCount() : progress1.getCount());
        progress1.setProgress(c, n, progress1.getMessage());
        publishProgress(progress0, progress1);

        cursor.moveToFirst();
        while (!cursor.isAfterLast())
        {
            int i_rowID = cursor.getColumnIndex(CalendarContract.Events._ID);
            if (i_rowID != -1)
            {
                long eventID = cursor.getLong(i_rowID);
                //Log.w("DEBUG", "addCalendarReminders: eventID: " + eventID);
                reminderValues.add(adapter.createReminderContentValues(calendarID, eventID, minutes, method));
            }
            cursor.moveToNext();
            c++;

            if (c % 128 == 0 || cursor.isAfterLast())
            {
                //Log.d("DEBUG", "addCalendarReminders: " + calendarID + ", numEntries: " + reminderValues.size());
                adapter.createCalendarReminders( reminderValues.toArray(new ContentValues[0]));
                reminderValues.clear();
            }
            if (c % 8 == 0 || cursor.isAfterLast()) {
                progress1.setProgress(c, progress1.getCount(), progress1.getMessage());
                publishProgress(progress0, progress1);
            }
        }
        cursor.close();
        return true;
    }

    /**
     * removeCalendarReminders
     */
    @TargetApi(14)
    private boolean removeCalendarReminders(@NonNull SuntimesCalendar calendar, @NonNull SuntimesCalendarTaskProgress progress0)
    {
        removeCalendarReminders(calendar.calendarName(), progress0);
        return true;
    }

    /**
     * removeCalendarReminders
     * @param calendar calendarName
     * @return number of entries deleted from reminders table
     */
    @TargetApi(14)
    public int removeCalendarReminders(String calendar, @Nullable SuntimesCalendarTaskProgress progress0)
    {
        long calendarID = adapter.queryCalendarID(calendar);
        if (calendarID != -1) {
            return removeCalendarReminders(calendarID, progress0);
        } else Log.w(TAG, "removeCalendarReminders: calendar not found! " + calendar);
        return 0;
    }

    @TargetApi(14)
    public int removeCalendarReminders(long calendarID, @Nullable SuntimesCalendarTaskProgress progress0)
    {
        Uri uri = SuntimesCalendarSyncAdapter.asSyncAdapter(CalendarContract.Reminders.CONTENT_URI);
        ArrayList<ContentProviderOperation> batch = new ArrayList<ContentProviderOperation>();
        int retValue = 0;

        ContentResolver contentResolver = contextRef.get().getContentResolver();
        Cursor cursor = adapter.queryCalendarEvents(calendarID);

        SuntimesCalendarTaskProgress progress1 = new SuntimesCalendarTaskProgress(0, cursor.getCount(), "");
        if (progress0 != null) {
            progress1.setProgress(0, cursor.getCount(), progress0.getMessage());
            publishProgress(progress0, progress1);
        }

        int c = 0;
        cursor.moveToFirst();
        while (!cursor.isAfterLast())
        {
            int i_rowID = cursor.getColumnIndex(CalendarContract.Events._ID);
            if (i_rowID != -1)
            {
                long eventID = cursor.getLong(i_rowID);
                final String[] args = new String[] { Long.toString(eventID) };
                batch.add(ContentProviderOperation.newDelete(uri)
                        .withSelection(CalendarContract.Reminders.EVENT_ID + " = ?", args).build());
            }
            cursor.moveToNext();
            c++;

            if (c % 128 == 0 || cursor.isLast())
            {
                try {
                    ContentProviderResult[] result = contentResolver.applyBatch(CalendarContract.AUTHORITY, batch);
                    retValue += (result != null ? result.length : 0);
                    //Log.d(TAG, "removeCalendarReminders: " + calendarID + ", removed: " + retValue);

                } catch (RemoteException | OperationApplicationException e) {
                    Log.e(TAG, "removeCalendarReminders: failed to remove reminders: " + e);
                }
                batch.clear();
            }
            if (c % 8 == 0 || cursor.isLast()) {
                progress1.setProgress(c, progress1.getCount(), progress1.getMessage());
                publishProgress(progress0, progress1);
            }
        }
        cursor.close();
        return retValue;
    }

}

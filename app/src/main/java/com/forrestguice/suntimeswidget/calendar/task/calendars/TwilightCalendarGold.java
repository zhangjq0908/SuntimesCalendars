/**
    Copyright (C) 2023 Forrest Guice
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

package com.forrestguice.suntimeswidget.calendar.task.calendars;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import com.forrestguice.suntimescalendars.R;
import com.forrestguice.suntimeswidget.calculator.core.CalculatorProviderContract;
import com.forrestguice.suntimeswidget.calendar.CalendarEventFlags;
import com.forrestguice.suntimeswidget.calendar.CalendarEventStrings;
import com.forrestguice.suntimeswidget.calendar.CalendarEventTemplate;
import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarAdapter;
import com.forrestguice.suntimeswidget.calendar.SuntimesCalendarSettings;
import com.forrestguice.suntimeswidget.calendar.TemplatePatterns;
import com.forrestguice.suntimeswidget.calendar.task.SuntimesCalendar;
import com.forrestguice.suntimeswidget.calendar.task.SuntimesCalendarTask;
import com.forrestguice.suntimeswidget.calendar.task.SuntimesCalendarTaskProgress;

import java.util.ArrayList;
import java.util.Arrays;

@SuppressWarnings("Convert2Diamond")
public class TwilightCalendarGold extends TwilightCalendarBase implements SuntimesCalendar
{
    private static final String CALENDAR_NAME = SuntimesCalendarAdapter.CALENDAR_TWILIGHT_GOLD;
    private static final int resID_calendarTitle = R.string.calendar_gold_twilight_displayName;
    private static final int resID_calendarSummary = R.string.calendar_gold_twilight_summary;

    protected String s_GOLDEN_HOUR, s_GOLDEN_HOUR_MORNING, s_GOLDEN_HOUR_EVENING;

    @Override
    public String calendarName() {
        return CALENDAR_NAME;
    }

    @Override
    public CalendarEventTemplate defaultTemplate() {
        return new CalendarEventTemplate("%cal", "%M @ %loc", "%loc");
    }

    @Override
    public void init(@NonNull Context context, @NonNull SuntimesCalendarSettings settings)
    {
        super.init(context, settings);

        calendarTitle = context.getString(resID_calendarTitle);
        calendarSummary = context.getString(resID_calendarSummary);
        calendarDesc = null;
        calendarColor = settings.loadPrefCalendarColor(context, calendarName());

        s_GOLDEN_HOUR_MORNING = context.getString(R.string.timeMode_golden_morning);
        s_GOLDEN_HOUR_EVENING = context.getString(R.string.timeMode_golden_evening);
        s_GOLDEN_HOUR = context.getString(R.string.timeMode_golden);
    }

    @Override
    public CalendarEventStrings defaultStrings() {
        return new CalendarEventStrings(s_GOLDEN_HOUR_MORNING, s_GOLDEN_HOUR_EVENING, s_GOLDEN_HOUR);
    }

    @Override
    public CalendarEventFlags defaultFlags()
    {
        boolean[] values = new boolean[2];
        Arrays.fill(values, true);
        return new CalendarEventFlags(values);
    }

    @Override
    public String flagLabel(int i)
    {
        switch (i) {
            case 0: return s_GOLDEN_HOUR_MORNING;
            case 1: return s_GOLDEN_HOUR_EVENING;
            default: return "";
        }
    }


    @Override
    public boolean initCalendar(@NonNull SuntimesCalendarSettings settings, @NonNull SuntimesCalendarAdapter adapter, @NonNull SuntimesCalendarTask task, @NonNull SuntimesCalendarTaskProgress progress0, @NonNull long[] window)
    {
        if (task.isCancelled()) {
            return false;
        }

        String calendarName = calendarName();
        if (!adapter.hasCalendar(calendarName)) {
            adapter.createCalendar(calendarName, calendarTitle, calendarColor);
        } else return false;

        long calendarID = adapter.queryCalendarID(calendarName);
        if (calendarID != -1)
        {
            Context context = contextRef.get();
            ContentResolver resolver = (context == null ? null : context.getContentResolver());
            if (resolver != null)
            {
                Uri uri = Uri.parse("content://" + CalculatorProviderContract.AUTHORITY + "/" + CalculatorProviderContract.QUERY_SUN + "/" + window[0] + "-" + window[1]);
                String[] projection = new String[] { CalculatorProviderContract.COLUMN_SUN_CIVIL_RISE, CalculatorProviderContract.COLUMN_SUN_GOLDEN_MORNING,
                                                     CalculatorProviderContract.COLUMN_SUN_GOLDEN_EVENING, CalculatorProviderContract.COLUMN_SUN_CIVIL_SET };   // 0, 1, 2, 3 .. expected order: civil (morning), golden (morning), golden (evening), civil (evening)

                Cursor cursor = resolver.query(uri, projection, null, null, null);
                if (cursor != null)
                {
                    String[] location = task.getLocation();
                    new SuntimesCalendarSettings().saveCalendarNote(context, calendarName, SuntimesCalendarSettings.NOTE_LOCATION_NAME, location[0]);

                    int c = 0;
                    String progressTitle = context.getString(R.string.summarylist_format, calendarTitle, location[0]);
                    int totalProgress = cursor.getCount();
                    SuntimesCalendarTaskProgress progress = new SuntimesCalendarTaskProgress(c, totalProgress, progressTitle);
                    task.publishProgress(progress0, progress);

                    boolean[] flags = SuntimesCalendarSettings.loadPrefCalendarFlags(context, calendarName, defaultFlags()).getValues();    // TODO
                    String[] strings = SuntimesCalendarSettings.loadPrefCalendarStrings(context, calendarName, defaultStrings()).getValues();    // 0:s_GOLDEN_HOUR_MORNING, 1:s_GOLDEN_HOUR_EVENING, 2:s_GOLDEN_HOUR
                    CalendarEventTemplate template = SuntimesCalendarSettings.loadPrefCalendarTemplate(context, calendarName, defaultTemplate());
                    ContentValues data = TemplatePatterns.createContentValues(null, this);
                    data = TemplatePatterns.createContentValues(data, task.getLocation());

                    ArrayList<ContentValues> eventValues = new ArrayList<>();
                    cursor.moveToFirst();
                    while (!cursor.isAfterLast() && !task.isCancelled())
                    {
                        if (flags[0]) {
                            createSunCalendarEvent(context, adapter, task, eventValues, calendarID, cursor, 0, template, data, strings[0], strings[0], strings[2]);    // civil twilight (morning), golden hour (morning)
                        }
                        if (flags[1]) {
                            createSunCalendarEvent(context, adapter, task, eventValues, calendarID, cursor, 2, template, data, strings[1], strings[1], strings[2]);    // golden hour (evening), civil twilight (evening)
                        }
                        cursor.moveToNext();
                        c++;

                        if (c % 128 == 0 || cursor.isLast()) {
                            adapter.createCalendarEvents( eventValues.toArray(new ContentValues[0]) );
                            eventValues.clear();
                        }
                        if (c % 8 == 0 || cursor.isLast()) {
                            progress.setProgress(c, totalProgress, progressTitle);
                            task.publishProgress(progress0, progress);
                        }
                    }
                    cursor.close();
                    createCalendarReminders(context, task, progress0);
                    return !task.isCancelled();

                } else {
                    lastError = "Failed to resolve URI! " + uri;
                    Log.e(getClass().getSimpleName(), lastError);
                    return false;
                }

            } else {
                lastError = "Unable to getContentResolver! ";
                Log.e(getClass().getSimpleName(), lastError);
                return false;
            }
        } else return false;
    }

}

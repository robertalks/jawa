#!/usr/bin/env python3
"""Generates the two big widget layouts and their widget-list previews:

  res/layout/widget_weather.xml       "JaWa":          big temperature left, icon + place right
  res/layout/widget_weather_icon.xml  "JaWa big icon": big icon left, temperature + place right

Both use the same view IDs, so the same code fills either one. Run from repo root.
"""
import os


def slot(prefix, i):
    return f'''
        <LinearLayout
            android:id="@+id/{prefix}_slot_{i}"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center_horizontal"
            android:orientation="vertical">
            <TextView
                android:id="@+id/{prefix}_label_{i}"
                style="@style/WidgetSmall"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
            <ImageView
                android:id="@+id/{prefix}_icon_{i}"
                android:layout_width="30dp"
                android:layout_height="30dp"
                android:layout_marginTop="2dp"
                android:layout_marginBottom="1dp"
                android:importantForAccessibility="no"
                android:src="@drawable/wx_cloudy" />
            <TextView
                android:id="@+id/{prefix}_temp_{i}"
                style="@style/WidgetValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        </LinearLayout>'''


def row(rid, prefix, n):
    return f'''
    <LinearLayout
        android:id="@+id/{rid}"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:orientation="horizontal">{"".join(slot(prefix, i) for i in range(n))}
    </LinearLayout>
'''


# Place, description, today's high / low: right-aligned text block used by both layouts.
TEXTS = '''
            <TextView
                android:id="@+id/place"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="end"
                android:ellipsize="end"
                android:maxWidth="150dp"
                android:maxLines="1"
                android:text="@string/widget_name"
                android:textColor="@color/widget_text"
                android:textSize="15sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/description"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="end"
                android:ellipsize="end"
                android:maxWidth="150dp"
                android:maxLines="1"
                android:text="Tap to load"
                android:textColor="@color/widget_text_dim"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/details"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="end"
                android:maxLines="1"
                android:textColor="@color/widget_text_faint"
                android:textSize="14sp" />'''


def temp_view(width, height, weight, gravity, max_sp, min_sp):
    return f'''
        <TextView
            android:id="@+id/current_temp"
            android:layout_width="{width}"
            android:layout_height="{height}"
            android:layout_weight="{weight}"
            android:autoSizeMaxTextSize="{max_sp}sp"
            android:autoSizeMinTextSize="{min_sp}sp"
            android:autoSizeStepGranularity="2sp"
            android:autoSizeTextType="uniform"
            android:fontFamily="sans-serif"
            android:gravity="{gravity}"
            android:includeFontPadding="false"
            android:maxLines="1"
            android:text="--°"
            android:textColor="@color/widget_text"
            android:textStyle="bold" />'''


# Moon phase label: a slim line at the top of the left side (icon / temperature below it).
MOON = '''
            <LinearLayout
                android:id="@+id/moon_row"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="2dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">
                <ImageView
                    android:id="@+id/moon_icon"
                    android:layout_width="16dp"
                    android:layout_height="16dp"
                    android:importantForAccessibility="no"
                    android:src="@drawable/moon_08" />
                <TextView
                    android:id="@+id/moon_text"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="5dp"
                    android:maxLines="1"
                    android:text=""
                    android:textColor="#FFE7A0"
                    android:textSize="13sp" />
            </LinearLayout>'''


# "JaWa": temperature fills the left; icon above the text on the right.
HEADER_TEMP = f'''
    <!-- Header takes all the height the days row doesn't need. The temperature on the
         left sizes itself to fill it, whatever size the launcher gives the widget. -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:minHeight="64dp"
        android:orientation="horizontal">

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:orientation="vertical">
{MOON}
{temp_view("match_parent", "0dp", 1, "start|center_vertical", 160, 40)}
        </LinearLayout>

        <!-- Right: weather icon above place, description, today's high / low -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
            android:layout_marginStart="8dp"
            android:gravity="end|center_vertical"
            android:orientation="vertical">

            <ImageView
                android:id="@+id/current_icon"
                android:layout_width="wrap_content"
                android:layout_height="0dp"
                android:layout_gravity="end"
                android:layout_marginBottom="4dp"
                android:layout_weight="1"
                android:adjustViewBounds="true"
                android:importantForAccessibility="no"
                android:maxWidth="96dp"
                android:maxHeight="96dp"
                android:scaleType="fitEnd"
                android:src="@drawable/wx_partly_day" />
{TEXTS}
        </LinearLayout>
    </LinearLayout>
'''

# "JaWa big icon": icon fills the left; temperature above the text on the right.
HEADER_ICON = f'''
    <!-- Header takes all the height the days row doesn't need. The icon on the left
         grows with it; the temperature on the right sizes itself to its space. -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:minHeight="64dp"
        android:orientation="horizontal">

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
            android:orientation="vertical">
{MOON}
            <ImageView
                android:id="@+id/current_icon"
                android:layout_width="wrap_content"
                android:layout_height="0dp"
                android:layout_gravity="center_horizontal"
                android:layout_weight="1"
                android:adjustViewBounds="true"
                android:importantForAccessibility="no"
                android:maxWidth="180dp"
                android:maxHeight="180dp"
                android:scaleType="fitCenter"
                android:src="@drawable/wx_partly_day" />
        </LinearLayout>

        <!-- Right: temperature above place, description, today's high / low -->
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:gravity="end|center_vertical"
            android:orientation="vertical">
{temp_view("match_parent", "0dp", 1, "end|bottom", 120, 32)}
{TEXTS}
        </LinearLayout>
    </LinearLayout>
'''

NAV = '''
    <!-- ‹  ━ • •  › : switch between saved places (hidden with only one place) -->
    <LinearLayout
        android:id="@+id/nav_row"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">
        <TextView
            android:id="@+id/prev"
            style="@style/WidgetArrow"
            android:text="‹" />
        <TextView
            android:id="@+id/dots"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center"
            android:includeFontPadding="false"
            android:maxLines="1"
            android:textColor="@color/widget_text"
            android:textSize="9sp" />
        <TextView
            android:id="@+id/next"
            style="@style/WidgetArrow"
            android:text="›" />
    </LinearLayout>
'''


def layout(header, title):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen_widget_layout.py. {title}
     Header, then the next days (hidden only if the widget is 1 row tall). -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:orientation="vertical"
    android:paddingStart="16dp"
    android:paddingTop="12dp"
    android:paddingEnd="16dp"
    android:paddingBottom="10dp">
{header}{row("days_row", "day", 5)}{NAV}</LinearLayout>
'''


# ---- static previews for the launcher's widget list (Android 12+): sample data

def preview_day(label, icon, hi, lo):
    return f'''
        <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
            android:gravity="center_horizontal" android:orientation="vertical">
            <TextView style="@style/WidgetSmall" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="{label}" />
            <ImageView android:layout_width="30dp" android:layout_height="30dp" android:layout_marginTop="2dp"
                android:layout_marginBottom="1dp" android:importantForAccessibility="no" android:src="@drawable/{icon}" />
            <TextView style="@style/WidgetValue" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="{hi}° {lo}°" />
        </LinearLayout>'''


DAYS = [("Today", "wx_showers_day", 18, 9), ("Fri", "wx_rain", 15, 8), ("Sat", "wx_overcast", 14, 7),
        ("Sun", "wx_partly_day", 17, 6), ("Mon", "wx_clear_day", 19, 7)]


def preview(xml):
    p = xml.split('<LinearLayout\n        android:id="@+id/days_row"')[0]
    p = p.replace('android:id="@+id/', 'android:tag="')  # no ids needed in a preview
    p = (p.replace('android:text="--°"', 'android:text="14°"')
         .replace('android:text="@string/widget_name"', 'android:text="Nepomuk"')
         .replace('android:text="Tap to load"', 'android:text="Partly cloudy"')
         .replace('android:textColor="@color/widget_text_faint"',
                  'android:text="18° / 9°"\n                android:textColor="@color/widget_text_faint"')
         .replace('android:text=""', 'android:text="Full moon"')
         .replace("Generated by tools/gen_widget_layout.py.", "Generated by tools/gen_widget_layout.py: widget-list preview."))
    return p + f'''<LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:orientation="horizontal">{"".join(preview_day(*d) for d in DAYS)}
    </LinearLayout>
</LinearLayout>
'''


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "app/src/main/res/layout")
    for name, preview_name, header, title in [
        ("widget_weather", "widget_preview", HEADER_TEMP, "JaWa: big temperature left, icon + place right."),
        ("widget_weather_icon", "widget_weather_icon_preview", HEADER_ICON,
         "JaWa big icon: big icon left, temperature + place right."),
    ]:
        xml = layout(header, title)
        open(os.path.join(out, name + ".xml"), "w").write(xml)
        open(os.path.join(out, preview_name + ".xml"), "w").write(preview(xml))
    print("ok")

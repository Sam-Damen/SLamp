package com.example.damen.slamp;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.commonsware.cwac.colormixer.ColorMixer;
import com.commonsware.cwac.colormixer.ColorMixerActivity;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

//TODO - Write Help, Alarm Data Packet, Bug Fix

public class MainActivity extends AppCompatActivity {

    // Default ColorMixer settings
    private static final int COLOR_REQUEST = 1337;
    private ColorMixer mixer = null;
    private static final String TAG = ColorMixer.class.getSimpleName();

    // Multicast settings
    MulticastSocket m_socket;
    InetAddress mMulticastAddress;

    // Current Orb command information
    private static int cRed;
    private static int cGreen;
    private static int cBlue;
    private static int cCommandOption;
    private static String cOrbIDs;
    private static String cOrbLedCount;

    //Switches
    SwitchCompat enableSwitch = null;
    SwitchCompat daylightSwitch = null;
    SwitchCompat colourSwitch = null;
    SwitchCompat partySwitch = null;

    //Shared Preferences (Global States)
    SharedPreferences prefs;
    private static Boolean enable, alarm, colour;

    //Alarm variables
    private static long nextAlarm;
    private static byte eMsd;



    @Override
    protected void onCreate(Bundle savedInstanceState) {

        /* Turn off multicast filter */
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = wm.createMulticastLock("debuginfo");
        multicastLock.acquire();

        //Setup
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar myToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        //Shared Preferences for State
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        //Setup Mixer
        mixer = (ColorMixer) findViewById(R.id.mixer);
        LoadSettings();
        mixer.setOnColorChangedListener(onColorChange);

        //Switches
        enableSwitch = (SwitchCompat) findViewById(R.id.enableSwitch);
        enableSwitch.setOnCheckedChangeListener(switchListener);
        daylightSwitch = (SwitchCompat) findViewById(R.id.daylightSwitch);
        daylightSwitch.setOnCheckedChangeListener(switchListener);
        colourSwitch = (SwitchCompat) findViewById(R.id.daylightColSwitch);
        colourSwitch.setOnCheckedChangeListener(switchListener);
        partySwitch = (SwitchCompat) findViewById(R.id.partySwitch);
        partySwitch.setOnCheckedChangeListener(switchListener);

        setSwitches();

        //Setup network connection
        MultiCastInstance();
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    /***********************************************************************************************
     *
     *                                      LISTENERS
     *
     **********************************************************************************************/


    CompoundButton.OnCheckedChangeListener switchListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged (CompoundButton buttonView, boolean isChecked) {


            switch(buttonView.getId()) {
                case R.id.enableSwitch:
                    if (isChecked) {
                        int argb = mixer.getColor();
                        setColor(Color.red(argb), Color.green(argb), Color.blue(argb), cOrbIDs, cOrbLedCount, 2);
                        sendPrefs(getApplicationContext(), "enable", true);
                        LoadSettings();
                    } else {
                        setColor(0, 0, 0, "1", "24", 1);
                        sendPrefs(getApplicationContext(), "enable", false);
                    }
                    break;
                case R.id.daylightSwitch:
                    if (isChecked) {
                        AlarmManager alarmManager = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
                        if (alarmManager.getNextAlarmClock() != null) {
                            nextAlarm = alarmManager.getNextAlarmClock().getTriggerTime();
                            //Print out time of the alarm
                            SimpleDateFormat sdf = new SimpleDateFormat("EEE h:mm a");
                            sdf.setTimeZone(TimeZone.getDefault());
                            Toast.makeText(getApplicationContext(),sdf.format(new Date(nextAlarm)), Toast.LENGTH_SHORT).show();
                            sendPrefs(getApplicationContext(), "alarm", true);

                            //Create data packet with alarm time
                            //TODO!
                            //Convert to minutes
                            nextAlarm = nextAlarm / 60000;
                            //break up into 3 bytes to send
                            byte lsd = (byte) (nextAlarm & 0xFF);
                            byte misd = (byte) ((nextAlarm >> 8) & 0xFF);
                            byte msd = (byte) ((nextAlarm >> 16) & 0xFF);
                            eMsd = (byte) ((nextAlarm >> 24) & 0xFF);
                            setColor(lsd, misd, msd, "1", "24", 5);

                        } else {
                            Toast.makeText(getApplicationContext(), "No Alarm Set", Toast.LENGTH_SHORT).show();
                        }


                    } else {
                        //Toggle off the alarm (on photon)
                        setColor(0, 0, 0, "1", "24", 5);
                        sendPrefs(getApplicationContext(), "alarm", false);
                    }
                    break;
                case R.id.daylightColSwitch:
                    if (isChecked) {
                        //TODO Set flag to send to Photon that we want to wake up to daylight colours

                        //Update colorMixer to 'daylight colour'
                        int argb =Color.argb(0xFF, 182,126,91);
                        mixer.setColor(argb);
                        sendPrefs(getApplicationContext(), "colour", true);
                        SaveSettings();
                        //Toggle on Enable Slamp if we enable Daylight Colour (but not on app start)
                        if (!enableSwitch.isChecked()) {
                            sendPrefs(getApplicationContext(), "enable", true);
                        }
                        LoadSettings();
                    }
                    break;
                case R.id.partySwitch:
                    if (isChecked) {
                        //Send party Mode to Orb
                        setColor(0, 0, 0, "1", "24", 3);

                        if (!enableSwitch.isChecked()) {
                            sendPrefs(getApplicationContext(), "enable", true);
                        }
                    }
                    break;
                default:
                    return;
            }

            //Update the switches after changes
            setSwitches();
        }
    };


    private ColorMixer.OnColorChangedListener onColorChange =
            new ColorMixer.OnColorChangedListener() {
                public void onColorChange(int argb) {

                    int red = Color.red(argb);
                    int green = Color.green(argb);
                    int blue = Color.blue(argb);

                    String OrbID = "1";
                    String OrbLedCount = "24";
                    SaveSettings();
                    setColor(red, green, blue, OrbID, OrbLedCount, 2);

                    //Toggle the switches if we change the colour mixer
                    if (colourSwitch.isChecked()) {
                        sendPrefs(getApplicationContext(), "colour", false);
                    } else {
                        sendPrefs(getApplicationContext(), "enable", true);
                    }
                    if (partySwitch.isChecked()) {
                        partySwitch.setChecked(false);
                    }
                    setSwitches();
                }
            };

    /***********************************************************************************************
     *
     *                                      METHODS
     *
     **********************************************************************************************/

    //Get the shared preferences and set state of switches accordingly
    private void setSwitches() {

        //Make sure we have latest states
        enable = prefs.getBoolean("enable", false);
        alarm = prefs.getBoolean("alarm", false);
        colour = prefs.getBoolean("colour", false);

        //Set state of Switches
        enableSwitch.setChecked(enable);
        daylightSwitch.setChecked(alarm);
        colourSwitch.setChecked(colour);

    }

    //Used to save shared preferences
    public void sendPrefs (Context context, String key, Boolean value) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(key, value);
        editor.apply();
    }


    public void MultiCastInstance() {
        try {
            mMulticastAddress = InetAddress.getByName("239.15.18.2");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        try {
            m_socket = new MulticastSocket(49692);
            m_socket.setSendBufferSize(50000);
            m_socket.setReceiveBufferSize(50000);

            m_socket.joinGroup(mMulticastAddress);
            m_socket.setLoopbackMode(true);
            m_socket.setTimeToLive(16);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void LoadSettings() {

        int argb = PreferenceManager.getDefaultSharedPreferences(this).getInt("OrbColors", 0);
        mixer.setColor(argb);
    }

    private void SaveSettings() {
        int argb = mixer.getColor();

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putInt("OrbColors", argb);
        editor.commit();
    }



    private void setColor(int red, int green, int blue, String orbID, String orbLedCount, int commandOption) {
        cRed = red;
        cGreen = green;
        cBlue = blue;
        cOrbIDs = orbID;
        cOrbLedCount = orbLedCount;
        cCommandOption = commandOption;

        new SendMultiCastData().execute("");
    }

    private class SendMultiCastData extends AsyncTask<String, Void, String> {
        protected String doInBackground(String... params) {
            {
                try {

                    List<String> Orbs = new ArrayList<String>();
                    Orbs.add(cOrbIDs);

                    if (cOrbIDs.contains(",")) {
                        Orbs.clear();
                        String[] splitcOrbIDs = cOrbIDs.split(",");

                        for (String orb : splitcOrbIDs) {
                            Orbs.add(orb);
                        }
                    }

                    for (String orb : Orbs) {
                        byte LedCount = Byte.parseByte(cOrbLedCount);
                        byte[] bytes = new byte[5 + LedCount * 3];

                        // Command identifier: C0FFEE
                        bytes[0] = (byte) 0xC0;
                        bytes[1] = (byte) 0xFF;
                        bytes[2] = (byte) 0xEE;
                        bytes[3] = (byte) cCommandOption;

                        // Orb ID
                        //Add extra data in when sending epoch time
                        if (cCommandOption == 5) {
                            bytes[4] = eMsd;
                        } else {
                            bytes[4] = Byte.parseByte(orb);
                        }

                        // RED / GREEN / BLUE
                        bytes[5] = (byte) cRed;
                        bytes[6] = (byte) cGreen;
                        bytes[7] = (byte) cBlue;

                        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, mMulticastAddress, 49692);
                        try {
                            m_socket.send(dp);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                }
            }
            return null;
        }
    }

    private ColorMixer.OnColorChangedListener onDialogSet =
            new ColorMixer.OnColorChangedListener() {
                public void onColorChange(int argb) {
                    mixer.setColor(argb);
                }
            };


    //Colour Palette Picker
    public void btnShowColorPicker(View v) {
        ColorPickerDialog colorPickerDialog = new ColorPickerDialog(this, mixer.getColor(), cOrbIDs, cOrbLedCount, new ColorPickerDialog.OnColorSelectedListener() {

            @Override
            public void onColorSelected(int color) {
                mixer.setColor(color);
            }
        });
        colorPickerDialog.show();
    }


    /***********************************************************************************************
     *
     *                                      Callbacks
     *
     **********************************************************************************************/

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected (MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_help) {
            //Launch Help Activity
            Intent intent = new Intent(MainActivity.this, HelpActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent result) {
        if (requestCode == COLOR_REQUEST && resultCode == RESULT_OK) {
            mixer.setColor(result.getIntExtra(ColorMixerActivity.COLOR,
                    mixer.getColor()));
        } else {
            super.onActivityResult(requestCode, resultCode, result);
        }
    }


}

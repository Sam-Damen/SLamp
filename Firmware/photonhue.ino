SYSTEM_THREAD(ENABLED);

#include <FastLED.h>
FASTLED_USING_NAMESPACE;

// UDP SETTINGS
#define SERVER_PORT 49692
#define DISCOVERY_PORT 49692
UDP client;
IPAddress multicastIP(239, 15, 18, 2);
bool connectLock  = false;

// ORB SETTINGS
unsigned int orbID = 1;

// LED settings
#define DATA_PIN    6
#define NUM_LEDS    24
CRGB leds[NUM_LEDS];

// UDP BUFFERS
#define BUFFER_SIZE  5 + 3 * NUM_LEDS
#define BUFFER_SIZE_DISCOVERY 5
#define TIMEOUT_MS   500
uint8_t buffer[BUFFER_SIZE];
uint8_t bufferDiscovery[BUFFER_SIZE_DISCOVERY];
unsigned long lastWiFiCheck = 0;

// SMOOTHING SETTINGS
#define SMOOTH_STEPS 50 // Steps to take for smoothing colors
#define SMOOTH_DELAY 4 // Delay between smoothing steps
#define SMOOTH_BLOCK 0 // Block incoming colors while smoothing

byte nextColor[3];
byte prevColor[3];
byte currentColor[3];
byte smoothStep = SMOOTH_STEPS;
unsigned long smoothMillis;

// CUSTOM COLOR CORRECTIONS
#define RED_CORRECTION 255
#define GREEN_CORRECTION 255
#define BLUE_CORRECTION 255

//EXTRA FUNCTIONS
unsigned int partyMode = 0;
unsigned int wakeupAlarm = 0;
long alarm = 0;
#define ALARM_LEAD_TIME 5

byte redA = 182;
byte greenA = 126;
byte blueA = 91;


void setup()
{
    // WiFi
    lastWiFiCheck = millis();
    initWiFi();
    
    //Time and Daylight Saving
    if (isDST()) {
        Time.zone(11);
    } else {
        Time.zone(10);
    }
    
    //Debugging
    Serial.begin(9600);
    
        
    // Leds - choose one correction method
    
    // 1 - FastLED predefined color correction
    //FastLED.addLeds<NEOPIXEL, DATA_PIN>(leds, NUM_LEDS).setCorrection(TypicalSMD5050);
    
    // 2 - Custom color correction
    FastLED.addLeds<NEOPIXEL, DATA_PIN>(leds, NUM_LEDS).setCorrection(CRGB(RED_CORRECTION, GREEN_CORRECTION, BLUE_CORRECTION));
	
	// Uncomment the below lines to dim the single built-in led to 5%
    ::RGB.control(true);
    ::RGB.brightness(5);
    ::RGB.control(false);
}

void initWiFi()
{
    if(!connectLock)
    {
        connectLock = true;
        
        // Wait for WiFi connection
        waitUntil(WiFi.ready);
        
        //  Client
        client.stop();
        client.begin(SERVER_PORT);
        //client.setBuffer(BUFFER_SIZE);
        
        // Multicast group
        client.joinMulticast(multicastIP);
        
        connectLock = false;
    }
}

void loop(){
    // Check WiFi connection every minute
    if(millis() - lastWiFiCheck > 500)
    {
        lastWiFiCheck = millis();
        if(!WiFi.ready() || !WiFi.connecting())
        {
            initWiFi();
        }
    }
    
    int packetSize = client.parsePacket();
    
    if(packetSize == BUFFER_SIZE){
        client.read(buffer, BUFFER_SIZE);
        //client.flush();
        unsigned int i = 0;
        
        // Look for 0xC0FFEE
        if(buffer[i++] == 0xC0 && buffer[i++] == 0xFF && buffer[i++] == 0xEE)
        {
            byte commandOptions = buffer[i++];
            byte rcvOrbID = buffer[i++];
            
            byte red =  buffer[i++];
            byte green =  buffer[i++];
            byte blue =  buffer[i++];
            
            // Command options
            // 1 = force off
            // 2 = use lamp smoothing and validate by Orb ID
            // 3 = Party Mode
            // 4 = validate by Orb ID
            // 5 = Flag Wakeup Alarm
            // 8 = discovery
            if(commandOptions == 1)
            {
                // Orb ID 0 = turn off all lights
                // Otherwise turn off selectively
                if(rcvOrbID == 0 || rcvOrbID == orbID)
                {
                    smoothStep = SMOOTH_STEPS;
                    forceLedsOFF();
                }
                
                partyMode = 0;
				
                return;
            }
            else if(commandOptions == 2)
            //'Normal' change of colour 
            {
                if(rcvOrbID != orbID)
                {
                    return;
                }
                
                partyMode = 0;
                setSmoothColor(red, green, blue);
                
                //Set colours for next alarm to show
                redA = red;
                greenA = green;
                blueA = blue;
                
            }
            else if (commandOptions == 3) 
            {
                partyMode = 1;
            }
            else if(commandOptions == 4)
            {
                if(rcvOrbID != orbID)
                {
                    return;
                }
                
                smoothStep = SMOOTH_STEPS;
                setColor(red, green, blue);
				setSmoothColor(red, green, blue);
				
				return;
            }
            else if (commandOptions == 5)
            {
                //Rebuild the alarm time
                alarm = (((long) red & 0xFF)) + (((long) green & 0xFF) << 8) + (((long) blue & 0xFF) << 16) + (((long) rcvOrbID & 0xFF) << 24);
                
                //Toggle the Alarm on and Off with Switch
                if ( (Time.now() / 60) >= (alarm - ALARM_LEAD_TIME) ) {
                    wakeupAlarm = 0;
                } else {
                    wakeupAlarm ^= 1;
                }
                
                Serial.println(wakeupAlarm);

            }
            else if(commandOptions == 8)
            {
                // Respond to remote IP address with Orb ID
                IPAddress remoteIP = client.remoteIP();
                bufferDiscovery[0] = orbID;
                
                client.sendPacket(bufferDiscovery, BUFFER_SIZE_DISCOVERY, remoteIP, DISCOVERY_PORT);
                
                // Clear buffer
                memset(bufferDiscovery, 0, sizeof(bufferDiscovery));
                return;
            }
        }
        
    } else if(packetSize > 0){
        // Got malformed packet
    }
    
    //Handle Wakeup Alarm
    if (wakeupAlarm == 1) {
        if ( (Time.now() / 60) >= (alarm - ALARM_LEAD_TIME) ) {
            //Turn on the previous colour
            setColor(redA,greenA,blueA);
        }
    }
    
    
    if (partyMode == 0) {
    
        if (smoothStep < SMOOTH_STEPS && millis() >= (smoothMillis + (SMOOTH_DELAY * (smoothStep + 1))))
        { 
            smoothColor();
        }
    } else {
        //Party Mode
        byte red = random(255);
        byte green = random(255);
        byte blue = random(255);
        
        setColor(red,green,blue);
        //Causing problems with recieving data? TODO
        delay(random(100,1000));
    }
}

// Set color
void setColor(byte red, byte green, byte blue)
{
    for (byte i = 0; i < NUM_LEDS; i++)
    {
        leds[i] = CRGB(red, green, blue); 
    }
    
    Serial.println(red);
    FastLED.show();
}


// Set a new color to smooth to
void setSmoothColor(byte red, byte green, byte blue)
{
    if (smoothStep == SMOOTH_STEPS || SMOOTH_BLOCK == 0)
    {
        if (nextColor[0] == red && nextColor[1] == green && nextColor[2] == blue)
        {
          return;
        }
        
        prevColor[0] = currentColor[0];
        prevColor[1] = currentColor[1];
        prevColor[2] = currentColor[2];
        
        nextColor[0] = red;
        nextColor[1] = green;
        nextColor[2] = blue;
        
        smoothMillis = millis();
        smoothStep = 0;
    }
}

// Display one step to the next color
void smoothColor()
{
    smoothStep++;
    currentColor[0] = prevColor[0] + (((nextColor[0] - prevColor[0]) * smoothStep) / SMOOTH_STEPS);
    currentColor[1] = prevColor[1] + (((nextColor[1] - prevColor[1]) * smoothStep) / SMOOTH_STEPS);
    currentColor[2] = prevColor[2] + (((nextColor[2] - prevColor[2]) * smoothStep) / SMOOTH_STEPS);
    
    setColor(currentColor[0], currentColor[1], currentColor[2]);
}

// Force all leds OFF
void forceLedsOFF()
{
    setColor(0,0,0);
    clearSmoothColors();
}

// Clear smooth color byte arrays
void clearSmoothColors()
{
    memset(prevColor, 0, sizeof(prevColor));
    memset(currentColor, 0, sizeof(nextColor));
    memset(nextColor, 0, sizeof(nextColor));
}

//Check if DST
bool isDST()
{
    int month = Time.month();
    int day = Time.day();
    int dow = Time.weekday();
    
    //Get the date of the previous Sunday
    int prevSun = day - dow;
    
    //May to September are not DST
    if ( (month > 4) && (month < 10) ) {
        return false;
    }
    
    //November to March are DST
    if ( (month > 10)  || (month < 4) ) {
        return true;
    }
    
    //After 1st Sunday in April is not DST
    if (month == 4) {
        return (prevSun <= 0);
    }
    
    //After 1st Sunday in October is DST
    if (month == 10) {
        return (prevSun >= 0);
    }
    
    return false;
}

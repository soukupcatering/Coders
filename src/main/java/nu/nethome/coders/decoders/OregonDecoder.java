/**
 * Copyright (C) 2005-2013, Stefan Strömberg <stefangs@nethome.nu>
 *
 * This file is part of OpenNetHome (http://www.nethome.nu).
 *
 * OpenNetHome is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenNetHome is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nu.nethome.coders.decoders;

import nu.nethome.util.plugin.Plugin;
import nu.nethome.util.ps.*;

import java.util.HashMap;
import java.util.Map;


@Plugin
public class OregonDecoder implements ProtocolDecoder {
    protected static final int IDLE = 0;
    protected static final int PREAMBLE = 1;
    protected static final int HI_IN = 2;
    protected static final int HI_BETWEEN = 3;
    protected static final int LO_IN = 4;
    protected static final int LO_BETWEEN = 5;

    protected static final int OREGON_SHORT = 490;
    protected static final int OREGON_LONG = 975;
    protected static final int MIN_PREAMBLE_PULSES = 16;
    protected static final BitString.Field NIBBLE = new BitString.Field(0, 4);
    protected static final int MAX_NIBBLES = 22;


    /**
     * The protocol is organized as a stream of nibbles (4 bits). These are the definitions of the different fields
     */
    public static final int SYNC_NIBBLE = 0;            // Sync, always 0xA
    public static final int SENSOR_TYPE = 1;            // 4 Nibbles
    public static final int CHANNEL = 5;                // 1 Nibble, channel set on the sensor
    public static final int IDENTITY = 6;               // 2 Nibbles, identity of sensor, changes on power loss
    public static final int FLAGS = 8;                  // 1 Nibble, flags
    public static final int LOW_BATTERY_BIT = 0x04;     // Low power indication bit in FLAGS

    public static final int TEMP_VALUE = 9;             // 3 Nibbles, temperature as BCD in reverse order
    public static final int TEMP_SIGN = 12;             // 1 Nibble, <> 0 means negative temperature
    public static final int MOISTURE_VALUE = 13;        // 2 Nibbles, moisture value as BCD in reverse order

    public static final int WIND_DIRECTION = 9;         // 1 Nibble, direction value, binary
    public static final int WIND_SPEED = 12;            // 3 Nibbles, speed value as BCD in reverse order
    public static final int AVG_WIND_SPEED = 15;        // 3 Nibbles, speed value as BCD in reverse order

    protected int m_State = IDLE;

    private BitString data = new BitString();

    protected int nibbleCounter = 0;
    protected int m_RepeatCount = 0;
    protected double m_LastValue = 0;
    protected ProtocolDecoderSink m_Sink = null;
    private int preambleCount;
    private boolean isInvertedBit;
    private int invertedBit;
    private byte[] nibbles = new byte[MAX_NIBBLES];
    private static Map<Integer, Sensor> sensors = new HashMap<Integer, Sensor>();
    private Sensor currentSensor;

    public OregonDecoder() {
        addSensor(new TempHumSensor());
        addSensor(new TempSensor());
        addSensor(new WindSensor());
    }

    public void setTarget(ProtocolDecoderSink sink) {
        m_Sink = sink;
    }

    public ProtocolInfo getInfo() {
        return new ProtocolInfo("Oregon", "Manchester", "Oregon Scientific", 19 * 4, 2);
    }

    private void addSensor(Sensor sensor) {
        for (int id : sensor.idCodes()) {
            sensors.put(id, sensor);
        }
    }

    private void selectSensor(int sensorId) {
        currentSensor = sensors.get(sensorId);
        if (currentSensor == null) {
            m_State = IDLE;
        }
    }

    protected boolean pulseCompare(double candidate, double standard) {
        return Math.abs(standard - candidate) < standard * 0.2;
    }

    protected void addBit(int b) {
        if (isInvertedBit) {
            invertedBit = b;
        } else if (b == invertedBit) {
            m_State = IDLE;
            return;
        } else {
            data.addMsb(b != 0);
            if (data.length() == 4) {
                addNibble((byte) data.extractInt(NIBBLE));
                data.clear();
            }
        }
        isInvertedBit = !isInvertedBit;
    }

    protected void addNibble(byte nibble) {
        nibbles[nibbleCounter++] = nibble;
        if (nibbleCounter == 6) {
            selectSensor(decodeSensorType(nibbles));
        }
        if (nibbleCounter > 6 && nibbleCounter >= currentSensor.messageLength()) {
            decodeMessage(nibbles);
        }
    }

    public void decodeMessage(byte[] nibbles) {
        int sensorType = decodeSensorType(nibbles);
        int channel = nibbles[CHANNEL];
        int rollingId = (nibbles[IDENTITY] << 4) + nibbles[IDENTITY + 1];
        int lowBattery = (nibbles[FLAGS] & LOW_BATTERY_BIT) != 0 ? 1 : 0;
        ProtocolMessage message = new ProtocolMessage("Oregon", sensorType, rollingId, 0);
        message.addField(new FieldValue("SensorId", sensorType));
        message.addField(new FieldValue("Channel", channel));
        message.addField(new FieldValue("Id", rollingId));
        message.addField(new FieldValue("LowBattery", lowBattery));
        if (currentSensor.hasTemperature()) {
            decodeTemperature(nibbles, message);
        }
        if (currentSensor.hasHumidity()) {
            decodeHumidity(nibbles, message);
        }
        if (currentSensor.hasWind()) {
            decodeWind(nibbles, message);
        }
        int checksum = (nibbles[currentSensor.messageLength() - 1] << 4) + nibbles[currentSensor.messageLength() - 2];
        int calculatedChecksum = 0;
        for (int i = 1; i < currentSensor.messageLength() - 2; i++) {
            calculatedChecksum += nibbles[i];
        }
        if (checksum == (calculatedChecksum & 0xFF)) {
            m_Sink.parsedMessage(message);
        }
        m_State = IDLE;
    }

    private int decodeHumidity(byte[] nibbles, ProtocolMessage message) {
        int moisture = nibbles[MOISTURE_VALUE + 1] * 10 + nibbles[MOISTURE_VALUE];
        message.addField(new FieldValue("Moisture", moisture));
        return moisture;
    }

    private int decodeTemperature(byte[] nibbles, ProtocolMessage message) {
        int temp = (nibbles[TEMP_VALUE + 2] * 100 + nibbles[TEMP_VALUE + 1] * 10 + nibbles[TEMP_VALUE]) * (nibbles[TEMP_SIGN] != 0 ? -1 : 1);
        message.addField(new FieldValue("Temp", temp));
        return temp;
    }

    private void decodeWind(byte[] nibbles, ProtocolMessage message) {
        int direction = nibbles[WIND_DIRECTION];
        message.addField(new FieldValue("Direction", direction));
        int speed = nibbles[WIND_SPEED + 2] * 100 + nibbles[WIND_SPEED + 1] * 10 + nibbles[WIND_SPEED];
        message.addField(new FieldValue("Wind", speed));
        int averageSpeed = nibbles[AVG_WIND_SPEED + 2] * 100 + nibbles[AVG_WIND_SPEED + 1] * 10 + nibbles[AVG_WIND_SPEED];
        message.addField(new FieldValue("AverageWind", averageSpeed));
    }

    private int decodeSensorType(byte[] nibbles) {
        return (nibbles[SENSOR_TYPE] << 12) + (nibbles[SENSOR_TYPE + 1] << 8) + (nibbles[SENSOR_TYPE + 2] << 4) + nibbles[SENSOR_TYPE + 3];
    }

    public int parse(double pulse, boolean isMark) {
        switch (m_State) {
            case IDLE: {
                if (pulseCompare(pulse, OREGON_LONG) && isMark) {
                    m_State = PREAMBLE;
                    preambleCount = 0;
                }
                break;
            }
            case PREAMBLE: {
                if (pulseCompare(pulse, OREGON_LONG)) {
                    preambleCount++;
                } else if (pulseCompare(pulse, OREGON_SHORT) && !isMark && (preambleCount > MIN_PREAMBLE_PULSES)) {
                    data.clear();
                    nibbleCounter = 0;
                    isInvertedBit = true;
                    m_State = HI_BETWEEN;
                } else {
                    m_State = IDLE;
                }
                break;
            }
            case HI_IN: {
                if (pulseCompare(pulse, OREGON_SHORT)) {
                    m_State = LO_BETWEEN;
                } else if (pulseCompare(pulse, OREGON_LONG)) {
                    m_State = LO_IN;
                    addBit(1);
                } else {
                    m_State = IDLE;
                }
                break;
            }
            case HI_BETWEEN: {
                if (pulseCompare(pulse, OREGON_SHORT)) {
                    m_State = LO_IN;
                    addBit(1);
                } else {
                    m_State = IDLE;
                }
                break;
            }
            case LO_IN: {
                if (pulseCompare(pulse, OREGON_SHORT)) {
                    m_State = HI_BETWEEN;
                    break;
                } else if (pulseCompare(pulse, OREGON_LONG)) {
                    m_State = HI_IN;
                    addBit(0);
                } else {
                    m_State = IDLE;
                }
                break;
            }
            case LO_BETWEEN: {
                if (pulseCompare(pulse, OREGON_SHORT)) {
                    m_State = HI_IN;
                    addBit(0);
                } else {
                    m_State = IDLE;
                }
                break;
            }
        }
        m_LastValue = pulse;
        return m_State;
    }

    public static abstract class Sensor {
        public abstract int[] idCodes();
        public abstract int messageLength();
        public boolean hasTemperature() {
            return false;
        }
        public boolean hasHumidity() {
            return false;
        }
        public boolean hasUvIndex() {
            return false;
        }
        public boolean hasWind() {
            return false;
        }
        public boolean hasRainMm() {
            return false;
        }
        public boolean hasRainIn() {
            return false;
        }
        public boolean hasBarometer() {
            return false;
        }
    }

    public static class TempHumSensor extends Sensor {

        private static final int codes[] = {0x1D20, 0xF824, 0xF8B4};

        @Override
        public int[] idCodes() {
            return codes;
        }

        @Override
        public int messageLength() {
            return 18;
        }

        @Override
        public boolean hasTemperature() {
            return true;
        }

        @Override
        public boolean hasHumidity() {
            return true;
        }
    }

    public static class TempSensor extends Sensor {
        private static final int codes[] = {0xEC40, 0xC844};
        @Override
        public int[] idCodes() {
            return codes;
        }
        @Override
        public int messageLength() {
            return 15;
        }
        @Override
        public boolean hasTemperature() {
            return true;
        }
    }
    public static class WindSensor extends Sensor {
        private static final int codes[] = {0x1984, 0x1994};
        @Override
        public int[] idCodes() {
            return codes;
        }
        @Override
        public int messageLength() {
            return 20;
        }
        @Override
        public boolean hasWind() {
            return true;
        }
    }
}

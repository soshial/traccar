/*
 * Copyright 2018 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.session.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.Date;

/**
 * Freematics API reference: https://freematics.com/pages/hub/api/
 */
public class FreematicsProtocolDecoder extends BaseProtocolDecoder {

    public FreematicsProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    /**
     * Event codes listed here:
     * https://github.com/stanleyhuangyc/Freematics/blob/master/firmware_v5/telelogger/teleclient.h
     *
     * @param sentence: EV=7,TS=2206661,ID=A0QWERT0,TM=1643449047
     */
    private Object decodeEvent(long deviceId, String sentence, Channel channel, SocketAddress remoteAddress) {

        Integer eventId = null;
        Long deviceTickerCounterMs = null;
        Date deviceTime = null;

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("=");
            String key = data[0];
            String value = data[1];
            switch (key) {
                case "ID": // identical to the string before '#' symbol
                    break;
                case "VIN": // vehicle ID, not always available to the device
                    break;
                case "EV":
                    eventId = Integer.valueOf(value);
                    break;
                case "TS":
                    // Device time ticker in milliseconds passed since Arduino board began running the current program.
                    // This unsigned 32-bit number will rollover (overflow, i.e. go back to zero) after ~50 days.
                    // For this reason, it can't be used for dates.
                    // https://www.arduino.cc/reference/en/language/functions/time/millis/
                    deviceTickerCounterMs = Long.parseLong(value);
                    break;
                case "TM":
                    // device timestamp in seconds (received from TM&TN params)
                    deviceTime = new Date(Long.parseLong(value) * 1000);
                    break;
                default:
                    break;
            }
        }

        if (channel != null && eventId != null && deviceTickerCounterMs != null) {
            // The server must respond to the device to confirm receival
            long timestampSec = System.currentTimeMillis() / 1000L;
            long timestampMicrosec = System.currentTimeMillis() % 1000L * 1000L;
            String message = String.format("1#EV=%d,RX=1,TS=%d,TM=%d,TN=%d", eventId, deviceTickerCounterMs,
                    timestampSec, timestampMicrosec);
            // TM= is an undocumented parameter; it is a Unix timestamp (of current server time), in seconds
            // Timestamp should be passed, so that the tracking device could sync time on EVENT_LOGIN
            // https://github.com/stanleyhuangyc/Freematics/blob/b8d7604bb61f/firmware_v5/telelogger/teleclient.cpp#L211
            message += '*' + Checksum.sum(message);
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));

            Position position = null;
            switch (eventId) {
                case 1: // EVENT_LOGIN
                case 2: // EVENT_LOGOUT
                case 3: // EVENT_SYNC
                case 4: // EVENT_RECONNECT
                case 5: // EVENT_COMMAND
                case 6: // EVENT_ACK
                case 7: // EVENT_PING
                    break;
                case 8: // EVENT_LOW_POWER
                    position = new Position(getProtocolName());
                    position.setDeviceId(deviceId);
                    if (deviceTime != null) {
                        getLastLocation(position, deviceTime);
                    }
                    position.set(Position.KEY_ALARM, Position.ALARM_LOW_POWER);
                    break;
                default:
                    return null;
            }
            return position;
        }

        return null;
    }

    /**
     * Freematics Packed Data Format documentation: https://freematics.com/pages/hub/freematics-data-logging-format/
     */
    private Object decodePosition(long deviceId, String sentence) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceId);

        // time
        DateBuilder dateBuilder = new DateBuilder();
        boolean receivedFixTime = false, receivedFixDate = false, receivedDeviceTicker = false;
        long deviceLocalTimeMs = 0; // from TM and TN params

        // cell tower
        CellTower cellTower = new CellTower();
        boolean receivedCellInfo = false;

        Double memsTemperature = null;
        Double cpuTemperature = null;

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("[=:]");
            int key;
            try {
                key = Integer.parseInt(data[0], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String value = data[1];
            switch (key) {
                case 0x0:
                    // see comment on line 63
                    receivedDeviceTicker = true;
                    break;
                case 0x1:
                    long timestampSec = Long.parseLong(value);
                    deviceLocalTimeMs += timestampSec * 1000L;
                    break;
                case 0x2:
                    int timestampMicrosec = Integer.parseInt(value);
                    deviceLocalTimeMs += timestampMicrosec / 1000L;
                    break;
                case 0x11:
                    receivedFixDate = true;
                    value = ("000000" + value).substring(value.length());
                    dateBuilder.setDateReverse(
                            Integer.parseInt(value.substring(0, 2)),
                            Integer.parseInt(value.substring(2, 4)),
                            Integer.parseInt(value.substring(4)));
                    break;
                case 0x10:
                    receivedFixTime = true;
                    value = ("00000000" + value).substring(value.length());
                    dateBuilder.setTime(
                            Integer.parseInt(value.substring(0, 2)),
                            Integer.parseInt(value.substring(2, 4)),
                            Integer.parseInt(value.substring(4, 6)),
                            Integer.parseInt(value.substring(6)) * 10);
                    break;
                case 0xA:
                    position.setValid(true);
                    position.setLatitude(Double.parseDouble(value));
                    break;
                case 0xB:
                    position.setValid(true);
                    position.setLongitude(Double.parseDouble(value));
                    break;
                case 0xC:
                    position.setAltitude(Double.parseDouble(value));
                    break;
                case 0xD:
                    position.setSpeed(UnitsConverter.knotsFromKph(Double.parseDouble(value)));
                    break;
                case 0xE:
                    position.setCourse(Integer.parseInt(value));
                    break;
                case 0xF:
                    position.set(Position.KEY_SATELLITES, Integer.parseInt(value));
                    break;
                case 0x12:
                    position.set(Position.KEY_HDOP, Integer.parseInt(value));
                    break;
                case 0x20:
                    position.set(Position.KEY_ACCELERATION, value);
                    break;
                case 0x23:
                    memsTemperature = Integer.parseInt(value) * 0.1;
                    break;
                case 0x24:
                    position.set(Position.KEY_POWER, Integer.parseInt(value) * 0.01);
                    break;
                case 0x40:
                    cellTower.setSignalStrength(Integer.parseInt(value));
                    receivedCellInfo = true;
                    break;
                case 0x41:
                    cellTower.setMobileCountryCode(Integer.parseInt(value));
                    receivedCellInfo = true;
                    break;
                case 0x42:
                    cellTower.setMobileNetworkCode(Integer.parseInt(value));
                    receivedCellInfo = true;
                    break;
                case 0x43:
                    cellTower.setLocationAreaCode(Integer.parseInt(value));
                    receivedCellInfo = true;
                    break;
                case 0x44:
                    cellTower.setCellId(Long.parseLong(value));
                    receivedCellInfo = true;
                    break;
                case 0x82:
                    cpuTemperature = Integer.parseInt(value) * 0.1;
                    break;
                case 0x92:
                    position.set(Position.KEY_IGNITION, Integer.parseInt(value) == 1);
                    break;
                case 0x104:
                    position.set(Position.KEY_ENGINE_LOAD, Integer.parseInt(value));
                    break;
                case 0x105:
                    position.set(Position.KEY_COOLANT_TEMP, Integer.parseInt(value));
                    break;
                case 0x10c:
                    position.set(Position.KEY_RPM, Integer.parseInt(value));
                    break;
                case 0x10d:
                    position.set(Position.KEY_OBD_SPEED, UnitsConverter.knotsFromKph(Integer.parseInt(value)));
                    break;
                case 0x111:
                    position.set(Position.KEY_THROTTLE, Integer.parseInt(value));
                    break;
                default:
                    position.set(Position.PREFIX_IO + key, value);
                    break;
            }
        }

        if (!receivedDeviceTicker) {
            return null;
        }

        if (deviceLocalTimeMs > 1000) {
            position.setDeviceTime(new Date(deviceLocalTimeMs));
        }

        if (receivedCellInfo) {
            position.setNetwork(new Network(cellTower));
        }

        // MEMS temperature sensor is more accurate, but MEMS might be turned off
        Double deviceTemperature = memsTemperature == null ? cpuTemperature : memsTemperature;
        if (deviceTemperature != null) {
            position.set(Position.KEY_DEVICE_TEMP, deviceTemperature);
        }

        if (!position.getValid()) {
            getLastLocation(position, position.getDeviceTime());
        }
        if (receivedFixDate && receivedFixTime) {
            position.setFixTime(dateBuilder.getDate());
        }

        return position;
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;
        int startIndex = sentence.indexOf('#');
        int endIndex = sentence.indexOf('*');

        if (startIndex > 0 && endIndex > 0) {
            String deviceIdentifier = sentence.substring(0, startIndex); // Example: A0QWERT0

            String checkSumReceived = sentence.substring(endIndex + 1);
            String checkSumCalculated = Checksum.sum(sentence.substring(0, endIndex));
            String content = sentence.substring(startIndex + 1, endIndex);

            if (!checkSumReceived.equals(checkSumCalculated)) {
                throw new IllegalArgumentException(String.format(
                        "Corrupted checksum for %s protocol: should be %s but received %s; received payload: %s",
                        getProtocolName(), checkSumCalculated, checkSumReceived, sentence)
                );
            }

            DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, deviceIdentifier);
            if (deviceSession == null) {
                return null;
            }

            if (content.startsWith("EV")) {
                // example: A0QWERT0#EV=7,TS=2206661,ID=A0QWERT0,*33
                return decodeEvent(deviceSession.getDeviceId(), content, channel, remoteAddress);
            } else {
                // example: M0ZR4X0#0:204391,11:140221,10:8445000,A:49.215920,B:18.737755,24:1252,20:0;0;0,82:47*B5
                return decodePosition(deviceSession.getDeviceId(), content);
            }
        }

        return null;
    }

}

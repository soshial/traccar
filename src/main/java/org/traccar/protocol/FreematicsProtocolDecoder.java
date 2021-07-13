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
import org.traccar.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
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
     * Event codes listed here: https://github.com/stanleyhuangyc/Freematics/blob/master/firmware_v5/telelogger/teleclient.h
     *
     * @param sentence: EV=7,TS=2206661,ID=A0QWERT0,
     */
    private Object decodeEvent(long deviceId, String sentence, Channel channel, SocketAddress remoteAddress) {

        Integer eventId = null;
        String time = null;

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
                    // This unsigned 32-bit number will overflow (go back to zero) after approximately 50 days.
                    time = value;
                    break;
                default:
                    break;
            }
        }

        if (channel != null && eventId != null && time != null) {
            // server must respond to the device to confirm receival
            String message = String.format("1#EV=%d,RX=1,TS=%s", eventId, time);
            message += '*' + Checksum.sum(message);
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
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
        boolean receivedFixTime = false, receivedFixDate = false, receivedDeviceMillis = false;

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
                    receivedDeviceMillis = true;
                    position.setDeviceTime(new Date(Long.parseLong(value)));
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
                    position.set(Position.KEY_BATTERY, Integer.parseInt(value) * 0.01);
                    break;
                case 0x81:
                    position.set(Position.KEY_RSSI, Integer.parseInt(value));
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

        if (!receivedDeviceMillis) {
            return null;
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
                throw new IllegalArgumentException(
                        String.format("Corrupted checksum for %s protocol: should be %s but received %s; received payload: %s",
                                getProtocolName(), checkSumCalculated, checkSumReceived, sentence));
            }

            DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, deviceIdentifier);
            if (deviceSession == null) {
                return null;
            }

            if (content.startsWith("EV")) {
                // example: A0QWERT0#EV=7,TS=2206661,ID=A0QWERT0,*33
                return decodeEvent(deviceSession.getDeviceId(), content, channel, remoteAddress);
            } else {
                // example: M0ZR4X0#0:204391,11:140221,10:8445000,A:49.215920,B:18.737755,C:410,D:0,E:208,24:1252,20:0;0;0,82:47*B5
                return decodePosition(deviceSession.getDeviceId(), content);
            }
        }

        return null;
    }

}

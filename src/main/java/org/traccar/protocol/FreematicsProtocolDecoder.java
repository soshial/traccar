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
import java.util.LinkedList;
import java.util.List;

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
     * Example string:
     * A0QWERT0#EV=7,TS=2206661,ID=A0QWERT0,*33
     *
     * @param channel
     * @param remoteAddress
     * @param sentence
     * @return
     */
    private Object decodeEvent(
            Channel channel, SocketAddress remoteAddress, String sentence) {

        DeviceSession deviceSession = null;
        Integer event = null;
        String time = null;

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("=");
            String key = data[0];
            String value = data[1];
            switch (key) {
                case "ID":
                case "VIN":
                    if (deviceSession == null) {
                        deviceSession = getDeviceSession(channel, remoteAddress, value);
                    }
                    break;
                case "EV":
                    event = Integer.valueOf(value);
                    break;
                case "TS":
                    // device timer counter in milliseconds
                    time = value;
                    break;
                default:
                    break;
            }
        }

        if (channel != null && deviceSession != null && event != null && time != null) {
            // traccar must respond to the device to confirm receival
            String message = String.format("1#EV=%d,RX=1,TS=%s", event, time);
            message += '*' + Checksum.sum(message);
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
        }

        return null;
    }

    /**
     * Freematics Packed Data Format documentation: https://freematics.com/pages/hub/freematics-data-logging-format/
     *
     * @param channel
     * @param remoteAddress
     * @param sentence
     * @param id
     * @return
     */
    private Object decodePosition(
            Channel channel, SocketAddress remoteAddress, String sentence, String id) {

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        List<Position> positions = new LinkedList<>();
        Position position = null;
        DateBuilder dateBuilder = null;

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
            if (key == 0x0) {
                if (position != null) {
                    position.setTime(dateBuilder.getDate());
                    positions.add(position);
                }
                position = new Position(getProtocolName());
                position.setDeviceId(deviceSession.getDeviceId());
                dateBuilder = new DateBuilder(new Date());
            } else if (position != null) {
                switch (key) {
                    case 0x11:
                        value = ("000000" + value).substring(value.length());
                        dateBuilder.setDateReverse(
                                Integer.parseInt(value.substring(0, 2)),
                                Integer.parseInt(value.substring(2, 4)),
                                Integer.parseInt(value.substring(4)));
                        break;
                    case 0x10:
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
        }

        // MEMS temperature sensor is more accurate, but MEMS might be turned off
        Double deviceTemperature = memsTemperature == null ? cpuTemperature : memsTemperature;
        if (deviceTemperature != null) {
            position.set(Position.KEY_DEVICE_TEMP, deviceTemperature);
        }

        if (position != null) {
            if (!position.getValid()) {
                getLastLocation(position, null);
            }
            position.setTime(dateBuilder.getDate());
            positions.add(position);
        }

        return positions.isEmpty() ? null : positions;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;
        int startIndex = sentence.indexOf('#');
        int endIndex = sentence.indexOf('*');

        if (startIndex > 0 && endIndex > 0) {
            String id = sentence.substring(0, startIndex);

            String checkSumReceived = sentence.substring(endIndex + 1);
            String checkSumCalculated = Checksum.sum(sentence.substring(0, endIndex));
            String content = sentence.substring(startIndex + 1, endIndex);

            if (!checkSumReceived.equals(checkSumCalculated)) {
                throw new IllegalArgumentException("Corrupted checksum for " + getProtocolName() + ": " + sentence);
            }

            if (content.startsWith("EV")) {
                return decodeEvent(channel, remoteAddress, content);
            } else {
                return decodePosition(channel, remoteAddress, content, id);
            }
        }

        return null;
    }

}

package tud.seemuh.nfcgate.network;

import android.nfc.Tag;
import android.util.Log;

import java.io.File;

import tud.seemuh.nfcgate.hce.DaemonConfiguration;
import tud.seemuh.nfcgate.network.meta.MetaMessage;
import tud.seemuh.nfcgate.reader.BroadcomWorkaround;
import tud.seemuh.nfcgate.reader.IsoDepReader;
import tud.seemuh.nfcgate.reader.NFCTagReader;
import tud.seemuh.nfcgate.reader.NfcAReader;
import tud.seemuh.nfcgate.util.Utils;
import tud.seemuh.nfcgate.network.c2c.C2C;
import tud.seemuh.nfcgate.network.c2s.C2S;
import tud.seemuh.nfcgate.network.meta.MetaMessage.Wrapper.MessageCase;
import tud.seemuh.nfcgate.network.c2c.C2C.Status.StatusCode;
import tud.seemuh.nfcgate.network.c2s.C2S.Session.SessionOpcode;
import tud.seemuh.nfcgate.hce.ApduService;
import tud.seemuh.nfcgate.util.sink.NfcComm;

/**
 * Implementation of the Callback interface. This class is used to parse incoming messages and works
 * with the HighLevelProtobufHandler class to process any logic required to follow the protocol.
 */
public class ProtobufCallback implements Callback {
    private final static String TAG = "ProtobufCallback";

    private ApduService apdu;
    private NFCTagReader mReader;
    private HighLevelNetworkHandler Handler = HighLevelProtobufHandler.getInstance();
    private BroadcomWorkaround mBroadcomWorkaroundRunnable;
    private Thread mBroadcomWorkaroundThread;

    public Callback setAPDUService(ApduService as) {
        apdu = as;
        return this;
    }

    public ProtobufCallback() {}

    @Override
    public void notifyBrokenPipe() {
        Handler.disconnectBrokenPipe();
    }

    public void shutdown() {
        disconnectCardWorkaround();
        if (mReader != null) mReader.closeConnection();
        mReader = null;
    }

    public void disconnectCardWorkaround() {
        if (mBroadcomWorkaroundThread != null) mBroadcomWorkaroundThread.interrupt();
        mBroadcomWorkaroundThread = null;
        mBroadcomWorkaroundRunnable = null;
    }

    /**
     * This function gets called by the LowLevelNetworkHandler upon receiving new data.
     * @param data: received bytes
     */
    @Override
    public void onDataReceived(byte[] data) {
        try {
            // Parse incoming data as a MetaMessage
            MetaMessage.Wrapper Wrapper = MetaMessage.Wrapper.parseFrom(data);

            handleWrapperMessage(Wrapper);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            // We have received a message in an invalid format.
            // Send error message
            Log.e(TAG, "onDataReceived: Message was malformed, discarding and sending error message");
            Handler.notifyInvalidMsgFormat();
        }
    }

    private void handleWrapperMessage(MetaMessage.Wrapper Wrapper) {
        // Determine which type of Message the MetaMessage contains
        if (Wrapper.getMessageCase() == MessageCase.DATA) {
            Log.i(TAG, "onDataReceived: MessageCase.DATA: Sending to handler");
            handleData(Wrapper.getData());
        }
        else if (Wrapper.getMessageCase() == MessageCase.NFCDATA) {
            Log.i(TAG, "onDataReceived: MessageCase:NFCDATA: Sending to handler");
            handleNFCData(Wrapper.getNFCData());
        }
        else if (Wrapper.getMessageCase() == MessageCase.SESSION) {
            Log.i(TAG, "onDataReceived: MessageCase.SESSION: Sending to handler");
            handleSession(Wrapper.getSession());
        }
        else if (Wrapper.getMessageCase() == MessageCase.STATUS) {
            Log.i(TAG, "onDataReceived: MessageCase.STATUS: Sending to handler");
            handleStatus(Wrapper.getStatus());
        }
        else if (Wrapper.getMessageCase() == MessageCase.ANTICOL) {
            Log.i(TAG, "onDataReceived: MessageCase.ANTICOL: Sending to handler");
            handleAnticol(Wrapper.getAnticol());
        }
        else {
            Log.e(TAG, "onDataReceived: Message fits no known case! This is fucked up");
            Handler.notifyUnknownMessageType();
        }
    }


    private void handleData(C2S.Data msg) {
        if (msg.hasBlob()) {
            try {
                // Decode binary blob into Wrapper message
                MetaMessage.Wrapper Wrapper = MetaMessage.Wrapper.parseFrom(msg.getBlob().toByteArray());

                // Now handle the wrapper Message
                handleWrapperMessage(Wrapper);
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                // We have received a message in an invalid format.
                // Send error message
                Log.e(TAG, "handleData: Message was malformed, discarding and sending error message");
                Handler.notifyInvalidMsgFormat();
            }
        } else if (msg.hasErrcode()) {
            if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_NO_SESSION) {
                Log.e(TAG, "Appearently, we sent a message without being in a session");
            } else if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_TRANSMISSION_FAILED) {
                Log.e(TAG, "Appearently, our partner dropped (Transmission failed)");
                // TODO Implement
            } else if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_UNKNOWN) {
                Log.e(TAG, "An unknown error occured. Interesting.");
            } else if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_NOERROR) {
                Log.d(TAG, "Message was forwarded successfully by the server. Doing nothing.");
            } else {
                Log.e(TAG, "Data message with unknown error code detected.");
                Handler.notifyInvalidMsgFormat();
            }
        } else {
            Log.e(TAG, "Got message without blob and errorcode. What.");
            Handler.notifyInvalidMsgFormat();
        }
    }

    private void handleAnticol(C2C.Anticol msg) {
        Log.i(TAG, "handleAnticol: got anticol values");

        byte[] a_atqa = msg.getATQA().toByteArray();
        byte atqa = a_atqa.length > 0 ? a_atqa[a_atqa.length-1] : 0;

        byte[] a_hist = msg.getHistoricalByte().toByteArray();
        byte hist = a_hist.length > 0 ? a_atqa[0] : 0;

        byte[] a_sak = msg.getSAK().toByteArray();
        byte sak = a_sak.length > 0 ? a_sak[0] : 0;

        byte[] uid = msg.getUID().toByteArray();

        DaemonConfiguration.getInstance().uploadConfiguration(atqa, sak, hist, uid);
        DaemonConfiguration.getInstance().enablePatch();

        // notify SinkManager
        Handler.notifySinkManager(new NfcComm(a_atqa, sak, a_hist, uid));
    }


    private void handleNFCData(C2C.NFCData msg) {
        if (msg.getDataSource() == C2C.NFCData.DataSource.READER) {
            // We received a signal FROM a reader device and are required to talk TO a card.
            if (mReader.isConnected()) {
                // Extract NFC Bytes from message
                byte[] cmd = msg.getDataBytes().toByteArray();

                // Logging
                Log.i(TAG, "HandleNFCData: Received message for a card, forwarding...");
                Log.d(TAG, "HandleNFCData: " + Utils.bytesToHex(cmd));

                // Notify SinkManager
                Handler.notifySinkManager(new NfcComm(NfcComm.Source.HCE, cmd));
                // communicate with the card
                byte[] bytesFromCard = mReader.sendCmd(cmd);

                // If the reply is null, the connection has been lost. Shut down Tag connection
                if (bytesFromCard == null) {
                    shutdown();
                } else {
                    // Reply is not null, forward it.
                    Handler.sendAPDUReply(bytesFromCard);
                    Log.i(TAG, "HandleNFCData: Received and forwarded reply from card");
                    Log.i(TAG, "HandleNFCData: BytesFromCard: " + Utils.bytesToHex(bytesFromCard));
                }
            } else {
                Log.e(TAG, "HandleNFCData: No NFC connection active");
                // There is no connected NFC device
                Handler.notifyNFCNotConnected();
            }
        } else {
            if (apdu != null) {
                Log.i(TAG, "HandleNFCData: Received a message for a reader, forwarding...");
                // We received a signal FROM a card and are required to talk TO a reader.
                byte[] reply = msg.getDataBytes().toByteArray();
                apdu.sendResponse(reply);

                // Notify SinkManager
                Handler.notifySinkManager(new NfcComm(NfcComm.Source.CARD, reply));
            } else {
                Log.e(TAG, "HandleNFCData: Received a message for a reader, but no APDU instance active.");
                Handler.notifyNFCNotConnected();
            }
        }
    }


    private void handleStatus(C2C.Status msg) {
        if (msg.getCode() == StatusCode.KEEPALIVE_REQ) {
            // Received keepalive request, reply with response
            Log.i(TAG, "handleStatus: Got Keepalive request, replying");
            Handler.sendKeepaliveReply();
        }
        else if (msg.getCode() == StatusCode.KEEPALIVE_REP) {
            // Got keepalive response, do nothing for now
            Log.i(TAG, "handleStatus: Got Keepalive response. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.NOT_IMPLEMENTED) {
            Log.e(TAG, "handleStatus: Other party sent NOT_IMPLEMENTED. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.UNKNOWN_ERROR) {
            Log.e(TAG, "handleStatus: Other party sent UNKNOWN_ERROR. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.UNKNOWN_MESSAGE) {
            Log.e(TAG, "handleStatus: Other party sent UNKNOWN_MESSAGE. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.INVALID_MSG_FMT) {
            Log.e(TAG, "handleStatus: Other party sent INVALID_MSG_FMT. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.READER_FOUND) {
            Log.d(TAG, "handleStatus: Other party sent READER_FOUND. Delegating to HighLevelProtobufHandler.");
            Handler.sessionPartnerAPDUModeOn();
        }
        else if (msg.getCode() == StatusCode.READER_REMOVED) {
            Log.d(TAG, "handleStatus: Other party sent READER_REMOVED. Delegating to HighLevelProtobufHandler.");
            Handler.sessionPartnerAPDUModeOff();
        }
        else if (msg.getCode() == StatusCode.CARD_FOUND) {
            Log.d(TAG, "handleStatus: Other party sent CARD_FOUND. Delegating to HighLevelProtobufHandler.");
            Handler.sessionPartnerReaderModeOn();
        }
        else if (msg.getCode() == StatusCode.CARD_REMOVED) {
            Log.d(TAG, "handleStatus: Other party sent CARD_REMOVED. Delegating to HighLevelProtobufHandler.");
            Handler.sessionPartnerReaderModeOff();
        }
        else if (msg.getCode() == StatusCode.NFC_NO_CONN) {
            Log.d(TAG, "handleStatus: Other party sent NFC_NO_CONN. Delegating to HighLevelProtobufHandler.");
            Handler.sessionPartnerNFCLost();
        }
        else {
            // Not implemented
            Log.e(TAG, "handleStatus: Message case not implemented");
            Handler.notifyNotImplemented();
        }
    }


    private void handleSession(C2S.Session msg) {
        if (msg.getOpcode() == SessionOpcode.SESSION_CREATE_FAIL) {
            Log.d(TAG, "handleSession: SESSION_CREATE_FAIL: Delegating to Handler");
            Handler.sessionCreateFailed(msg.getErrcode());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_CREATE_SUCCESS) {
            Log.d(TAG, "handleSession: SESSION_CREATE_SUCCESS: Delegating to Handler");
            // Notify handler about session secret
            Handler.confirmSessionCreation(msg.getSessionSecret());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_JOIN_FAIL) {
            Log.d(TAG, "handleSession: SESSION_JOIN_FAIL: Delegating to Handler");
            Handler.sessionJoinFailed(msg.getErrcode());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_JOIN_SUCCESS) {
            Log.d(TAG, "handleSession: SESSION_JOIN_SUCCESS: Delegating to Handler");
            Handler.confirmSessionJoin();
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_LEAVE_FAIL) {
            Log.d(TAG, "handleSession: SESSION_LEAVE_FAIL: Delegating to Handler");
            Handler.sessionLeaveFailed(msg.getErrcode());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_LEAVE_SUCCESS) {
            Log.d(TAG, "handleSession: SESSION_LEAVE_SUCCESS: Delegating to Handler");
            Handler.confirmSessionLeave();
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_PEER_JOINED) {
            Log.d(TAG, "handleSession: SESSION_PEER_JOINED: Delegating to Handler");
            Handler.sessionPartnerJoined();
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_PEER_LEFT) {
            Log.d(TAG, "handleSession: SESSION_PEER_LEFT: Delegating to Handler");
            Handler.sessionPartnerLeft();
        }
        else {
            Log.e(TAG, "handleSession: Unknown Opcode!");
            Handler.notifyInvalidMsgFormat();
        }
    }


    /**
     * Called on nfc tag intent
     * @param tag nfc tag
     * @return true if a supported tag is found
     */
    public boolean setTag(Tag tag) {

        boolean found_supported_tag = false;

        //identify tag type
        for(String type: tag.getTechList()) {
            Log.i(TAG, "setTag: Tag TechList: " + type);
            if("android.nfc.tech.IsoDep".equals(type)) {
                found_supported_tag = true;

                mReader = new IsoDepReader(tag);
                Log.d(TAG, "setTag: Chose IsoDep technology.");
                break;
            } else if("android.nfc.tech.NfcA".equals(type)) {
                found_supported_tag = true;

                mReader = new NfcAReader(tag);
                Log.d(TAG, "setTag: Chose NfcA technology.");
                break;
            }
        }

        //set callback when data is received
        if(found_supported_tag){
            Log.d(TAG, "setTag: Setting callback");
            LowLevelTCPHandler.getInstance().setCallback(this);
            byte[] uid = mReader.getUID();
            byte[] atqa = mReader.getAtqa();
            byte sak = mReader.getSak();
            byte[] hist = mReader.getHistoricalBytes();
            Log.d(TAG, "setTag: UID:  " + Utils.bytesToHex(uid));
            Log.d(TAG, "setTag: ATQA: " + Utils.bytesToHex(atqa));
            Log.d(TAG, "setTag: SAK:  " + Utils.bytesToHex(sak));
            Log.d(TAG, "setTag: HIST: " + Utils.bytesToHex(hist));
            Handler.sendAnticol(atqa, sak, hist, uid);
        }

        // Check if the device is running a specific Broadcom chipset (used in the Nexus 4, for example)
        // The drivers for this chipset contain a bug which lead to DESFire cards being set into a specific mode
        // We want to avoid that, so we use a workaround. This starts another thread that prevents
        // the keepalive function from running and thus prevents it from setting the mode of the card
        // Other devices work fine without this workaround, so we only activate it on bugged chipsets
        // TODO Only activate for DESFire cards
        File bcmdevice = new File("/dev/bcm2079x-i2c");
        if (bcmdevice.exists()) {
            Log.i(TAG, "setTag: Problematic broadcom chip found, activate workaround");
            // Initialize a runnable object
            mBroadcomWorkaroundRunnable = new BroadcomWorkaround(tag);
            // Start up a new thread
            mBroadcomWorkaroundThread = new Thread(mBroadcomWorkaroundRunnable);
            mBroadcomWorkaroundThread.start();
            Handler.notifyCardWorkaroundConnected();
        } else {
            Log.i(TAG, "setTag: No problematic broadcom chipset found, leaving workaround inactive");
        }

        return found_supported_tag;
    }
}
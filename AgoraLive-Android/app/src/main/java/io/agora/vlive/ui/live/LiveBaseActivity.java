package io.agora.vlive.ui.live;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Map;

import io.agora.framework.PreprocessorFaceUnity;
import io.agora.framework.RtcVideoConsumer;
import io.agora.framework.VideoModule;
import io.agora.framework.channels.CameraVideoChannel;
import io.agora.framework.channels.ChannelManager;
import io.agora.rtc.Constants;
import io.agora.rtc.IRtcEngineEventHandler;
import io.agora.rtc.RtcEngine;
import io.agora.rtc.video.VideoCanvas;
import io.agora.rtm.ErrorInfo;
import io.agora.rtm.ResultCallback;
import io.agora.rtm.RtmChannelAttribute;
import io.agora.rtm.RtmChannelMember;
import io.agora.vlive.R;
import io.agora.vlive.agora.rtc.RtcEventHandler;
import io.agora.vlive.agora.rtm.RtmMessageManager;
import io.agora.vlive.agora.rtm.RtmMessageListener;
import io.agora.vlive.agora.rtm.model.GiftRankMessage;
import io.agora.vlive.agora.rtm.model.NotificationMessage;
import io.agora.vlive.agora.rtm.model.PKMessage;
import io.agora.vlive.agora.rtm.model.SeatStateMessage;
import io.agora.vlive.ui.BaseActivity;
import io.agora.vlive.utils.Global;

/**
 * Common capabilities of a live room. Such as, camera capture，
 * , agora rtc, messaging, permission check, communication with
 * the back-end server, and so on.
 */
public abstract class LiveBaseActivity extends BaseActivity
        implements RtcEventHandler, RtmMessageListener {
    protected static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private static final int PERMISSION_REQ = 1;

    // values of a live room
    protected String roomName;
    protected String roomId;
    protected boolean isOwner;
    protected String ownerId;
    protected boolean isHost;
    protected int myRtcRole;
    protected int ownerRtcUid;
    protected int tabId;

    // Current rtc channel generated by server
    // and obtained when entering the room.
    protected String rtcChannelName;

    // Used to receive video from video channel,
    // and push video frames to rtc engine
    private RtcVideoConsumer mRtcConsumer;

    private RtmMessageManager mMessageManager;
    private CameraVideoChannel mCameraChannel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        keepScreenOn(getWindow());
        checkPermissions();
    }

    protected void checkPermissions() {
        if (!permissionArrayGranted()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQ);
        } else {
            performInit();
        }
    }

    private boolean permissionGranted(String permission) {
        return ContextCompat.checkSelfPermission(
                this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean permissionArrayGranted() {
        boolean granted = true;
        for (String per : PERMISSIONS) {
            if (!permissionGranted(per)) {
                granted = false;
                break;
            }
        }
        return granted;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQ) {
            if (permissionArrayGranted()) {
                performInit();
            } else {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void performInit() {
        initRoom();
        onPermissionGranted();
    }

    private void initRoom() {
        Intent intent = getIntent();
        roomName = intent.getStringExtra(Global.Constants.KEY_ROOM_NAME);
        roomId = intent.getStringExtra(Global.Constants.KEY_ROOM_ID);
        isOwner = intent.getBooleanExtra(Global.Constants.KEY_IS_ROOM_OWNER, false);
        ownerId = intent.getStringExtra(Global.Constants.KEY_ROOM_OWNER_ID);
        isHost = isOwner;
        myRtcRole = isOwner ? Constants.CLIENT_ROLE_BROADCASTER : Constants.CLIENT_ROLE_AUDIENCE;
        tabId = intent.getIntExtra(Global.Constants.TAB_KEY, -1);

        mMessageManager = RtmMessageManager.instance();
        mMessageManager.init(rtmClient());
        mMessageManager.registerMessageHandler(this);
        mMessageManager.setCallbackThread(new Handler(getMainLooper()));

        proxy().registerProxyListener(this);
        registerRtcHandler(this);

        initVideoModule();
        mRtcConsumer = new RtcVideoConsumer(VideoModule.instance());
        mCameraChannel = (CameraVideoChannel) VideoModule.instance()
                .getVideoChannel(ChannelManager.ChannelID.CAMERA);
    }

    private void initVideoModule() {
        VideoModule videoModule = VideoModule.instance();
        if (videoModule.hasInitialized()) return;
        videoModule.init(getApplicationContext());

        int channelId = ChannelManager.ChannelID.CAMERA;
        videoModule.setPreprocessor(channelId,
                new PreprocessorFaceUnity(getApplicationContext()));
        // enables off-screen frame consumers like rtc engine.
        videoModule.enableOffscreenMode(channelId, true);
        videoModule.startChannel(channelId);
    }

    protected abstract void onPermissionGranted();

    protected RtmMessageManager getMessageManager() {
        return mMessageManager;
    }

    protected void joinRtcChannel() {
        rtcEngine().setClientRole(myRtcRole);
        rtcEngine().setVideoSource(mRtcConsumer);
        setVideoConfiguration();
        rtcEngine().joinChannel(config().getUserProfile().getRtcToken(),
                rtcChannelName, null, (int) config().getUserProfile().getAgoraUid());
    }

    protected SurfaceView setupRemoteVideo(int uid) {
        SurfaceView surfaceView = RtcEngine.CreateRendererView(this);
        rtcEngine().setupRemoteVideo(new VideoCanvas(
                surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid));
        return surfaceView;
    }

    protected void remoteRemoteVideo(int uid) {
        rtcEngine().setupRemoteVideo(new VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid));
    }

    protected void setVideoConfiguration() {
        rtcEngine().setVideoEncoderConfiguration(config().createVideoEncoderConfig());
    }

    protected void startCameraCapture() {
        if (mCameraChannel != null && !mCameraChannel.hasCaptureStarted()) {
            Log.i("LiveBaseActivity", "startCameraCapture");
            enablePreProcess(config().isBeautyEnabled());
            mCameraChannel.startCapture();
        }
    }

    protected void switchCamera() {
        if (mCameraChannel != null) {
            mCameraChannel.switchCamera();
        }
    }

    protected void stopCameraCapture() {
        if (mCameraChannel != null && mCameraChannel.hasCaptureStarted()) {
            mCameraChannel.stopCapture();
        }
    }

    protected void enablePreProcess(boolean enabled) {
        if (mCameraChannel != null) {
            mCameraChannel.enablePreProcess(enabled);
        }
    }

    protected void joinRtmChannel() {
        mMessageManager.joinChannel(rtcChannelName, new ResultCallback<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                Log.i(LiveBaseActivity.class.getSimpleName(), "on rtm join channel success");
            }

            @Override
            public void onFailure(ErrorInfo errorInfo) {

            }
        });
    }

    protected void leaveRtmChannel(ResultCallback<Void> callback) {
        mMessageManager.leaveChannel(callback);
    }

    @Override
    public void onRtmConnectionStateChanged(int state, int reason) {

    }

    @Override
    public void onRtmTokenExpired() {

    }

    @Override
    public void onRtmPeersOnlineStatusChanged(Map<String, Integer> map) {

    }

    @Override
    public void onRtmMemberCountUpdated(int memberCount) {

    }

    @Override
    public void onRtmAttributesUpdated(List<RtmChannelAttribute> attributeList) {

    }

    @Override
    public void onRtmMemberJoined(RtmChannelMember rtmChannelMember) {

    }

    @Override
    public void onRtmMemberLeft(RtmChannelMember rtmChannelMember) {

    }

    @Override
    public void onRtmInvitedByOwner(String ownerId, String nickname, int index) {

    }

    @Override
    public void onRtmAppliedForSeat(String ownerId, String nickname, int index) {

    }

    @Override
    public void onRtmInvitationAccepted(String peerId, String nickname, int index) {

    }

    @Override
    public void onRtmApplicationAccepted(String peerId, String nickname, int index) {

    }

    @Override
    public void onRtmInvitationRejected(String peerId, String nickname) {

    }

    @Override
    public void onRtmApplicationRejected(String peerId, String nickname) {

    }

    @Override
    public void onRtmPkReceivedFromAnotherHost(String peerId, String nickname, String pkRoomId) {

    }

    @Override
    public void onRtmPkAcceptedByTargetHost(String peerId, String nickname) {

    }

    @Override
    public void onRtmPkRejectedByTargetHost(String peerId, String nickname) {

    }

    @Override
    public void onRtmChannelMessageReceived(String peerId, String nickname, String content) {

    }

    @Override
    public void onRtmRoomGiftRankChanged(int total, List<GiftRankMessage.GiftRankItem> list) {

    }

    @Override
    public void onRtmOwnerStateChanged(String userId, String userName, int uid, int enableAudio, int enableVideo) {

    }

    @Override
    public void onRtmSeatStateChanged(List<SeatStateMessage.SeatStateMessageDataItem> data) {

    }

    @Override
    public void onRtmPkStateChanged(PKMessage.PKMessageData messageData) {

    }

    @Override
    public void onRtmGiftMessage(String fromUserId, String fromUserName, String toUserId, String toUserName, int giftId) {

    }

    @Override
    public void onRtmChannelNotification(int total, List<NotificationMessage.NotificationItem> list) {

    }

    @Override
    public void onRtcJoinChannelSuccess(String channel, int uid, int elapsed) {

    }

    @Override
    public void onRtcRemoteVideoStateChanged(int uid, int state, int reason, int elapsed) {

    }

    @Override
    public void onRtcStats(IRtcEngineEventHandler.RtcStats stats) {

    }

    @Override
    public void onChannelMediaRelayStateChanged(int state, int code) {

    }

    @Override
    public void onChannelMediaRelayEvent(int code) {

    }

    @Override
    public void finish() {
        super.finish();
        removeRtcHandler(this);
        rtcEngine().leaveChannel();
        mMessageManager.removeMessageHandler(this);
        mMessageManager.leaveChannel(new ResultCallback<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                Log.i("messagemanager", "rtm leave success");
            }

            @Override
            public void onFailure(ErrorInfo errorInfo) {

            }
        });
    }
}

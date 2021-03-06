package com.example.gesturerecognitionapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import weka.classifiers.trees.J48;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;


public class MainActivity extends AppCompatActivity {

    private final static int REQUEST_ENABLE_BT = 1;
    private Button resetButton;

    private TextView bt_output;

    private MqttAndroidClient mqttAndroidClient;

    private BufferedReader reader;

    private MqttAndroidClient client;

    private String BROKER_URL = "tcp://m14.cloudmqtt.com:16303";

    boolean isUnlocked = false;

    BluetoothAdapter mBluetoothAdapter;
    ConnectedThread mConnectedThread;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    Instances TrainData;
    J48 mTree;
    DenseInstance instance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ConnectToBluetooth();

        //Reading in the train data from file
        InputStream isTrainData = getResources().openRawResource(R.raw.combinedraw);
        resetButton = (Button) findViewById(R.id.resetbutton);

        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                values.clear();
            }
        });

        reader = new BufferedReader(new InputStreamReader(isTrainData));

        bt_output = (TextView) findViewById(R.id.bt_output);
        bt_output.setText("Unlock the device...");

        //Creating the train data Instances
        TrainData = null;
        try {
            TrainData = new Instances(reader);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // setting class attribute
        TrainData.setClassIndex(TrainData.numAttributes() - 1);

        //Instantiating the learning tree
        mTree = new J48();
        try {
            mTree.buildClassifier(TrainData);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*Reading from bluetooth*/
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            mmDevice = device;
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) { }
            mmSocket = tmp;
        }
        public void run() {
            mBluetoothAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
            } catch (IOException connectException) {
                try {
                    mmSocket.close();
                } catch (IOException closeException) { }
                return;
            }

            mConnectedThread = new ConnectedThread(mmSocket);
            mConnectedThread.start();
        }
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    /*Reading from bluetooth*/
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }
        /*Send message if we find ","*/
        public void run() {

            byte[] buffer = new byte[1024];
            int begin = 0;
            int bytes = 0;
            while (true) {
                //bt_output.setText(buffer[0]);
                try {
                    bytes += mmInStream.read(buffer, bytes, buffer.length - bytes);
                    for(int i = begin; i < bytes; i++) {

                        if(buffer[i] == ",".getBytes()[0]) {
                            mHandler.obtainMessage(1, begin, i, buffer).sendToTarget();
                            begin = i + 1;
                            if(i == bytes - 1) {
                                bytes = 0;
                                begin = 0;
                            }
                        }
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) { }
        }
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    /*Revive the data from bluetooth*/
    ArrayList<Double> values = new ArrayList<>();
    ArrayList<Double> normAcc = new ArrayList<>();
    ArrayList<Double> normGyro = new ArrayList<>();
    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            byte[] writeBuf = (byte[]) msg.obj;
            int begin = (int)msg.arg1;
            int end = (int)msg.arg2;

            switch(msg.what) {
                case 1:
                    String writeMessage = new String(writeBuf);
                    writeMessage = writeMessage.substring(begin, end);
                    try
                    {
                        //Try parsing the message recieved to integer
                        double val = Double.parseDouble(writeMessage.trim());
                        values.add(val);

                        //Wait to have 120 values to start the identifying process
                        if(values.size() > 119)
                        {
                            IdentifyGesture();
                        }
                    }
                    catch (NumberFormatException nfe)
                    {
                        //Handle nfe?
                    }
                    break;
            }
        }
    };


    private void IdentifyGesture()
    {
        // create a DenseInstance based on your live Acc and Gyr data
        //Instantiating the instance for live recognition. This is what is later filled with the live data
        instance = new DenseInstance(121); // assuming that you have 120 values + one class label

       /* int k = 0;
        while(k < 120) { //Divide gyro and acc data
            for (int j = 0; j < 3; j++, k++) {
                normAcc.add(values.get(k));
            }
            for (int j = 0; j < 3; j++, k++) {
                normGyro.add(values.get(k));
            }
        }

        //Find min and max for acc- and gyro data
        double accMin = normAcc.get(0), accMax = normAcc.get(0), gyrMin = normGyro.get(0), gyrMax = normGyro.get(0);
        for (int i = 0; i < values.size() / 2; i++) {
            if (normAcc.get(i) < accMin)
                accMin = normAcc.get(i);
            if (normAcc.get(i) > accMax)
                accMax = normAcc.get(i);
            if (normGyro.get(i) < gyrMin)
                gyrMin = normGyro.get(i);
            if (normGyro.get(i) > gyrMax)
                gyrMax = normGyro.get(i);
        }

        //Smooth acc data using moving window avarage
        for (int i = 0; i < normAcc.size(); i++) {

            if (i < 3) {}
            else if (i > 2 && i < 6){
                //normAcc.set(i, (normAcc.get(i) + normAcc.get(i - 1)) / 2);
                normAcc.set(i, (normAcc.get(i) + normAcc.get(i - 3)) / 2);
            }
            else if (i > 5 && i < 9){
                normAcc.set(i, (normAcc.get(i) + normAcc.get(i - 3) + normAcc.get(i - 6)) / 3);
            }
            else if (i > 8 && i <  12){
                normAcc.set(i, (normAcc.get(i) + normAcc.get(i - 3) + normAcc.get(i - 6) + normAcc.get(i - 9)) / 4);
            }
            else{
                normAcc.set(i, (normAcc.get(i) + normAcc.get(i - 3) + normAcc.get(i - 6) + normAcc.get(i - 9) + normAcc.get(i - 12)) / 5);
            }
        }

        //Smoothing gyro data using moving window avarage
        for (int i = 0; i < normGyro.size(); i++) {

            if (i < 3) {}
            else if (i > 2 && i < 6){
                normGyro.set(i, (normGyro.get(i) + normGyro.get(i - 3)) / 2);
            }
            else if (i > 5 && i < 9){
                normGyro.set(i, (normGyro.get(i) + normGyro.get(i - 3) + normGyro.get(i - 6)) / 3);
            }
            else if (i > 8 && i <  12){
                normGyro.set(i, (normGyro.get(i) + normGyro.get(i - 3) + normGyro.get(i - 6) + normGyro.get(i - 9)) / 4);
            }
            else{
                normGyro.set(i, (normGyro.get(i) + normGyro.get(i - 3) + normGyro.get(i - 6) + normGyro.get(i - 9) + normGyro.get(i - 12)) / 5);
            }
        }

        //Normalize acc and gyro data between 0 and 200
        for (int i = 0; i < normGyro.size(); i++) {
            Double nG = ((normGyro.get(i) - gyrMin) / (gyrMax - gyrMin)) * 200;
            Double nA = ((normAcc.get(i) - accMin) / (accMax - accMin)) * 200;
            normGyro.set(i, nG.doubleValue());
            normAcc.set(i, nA.doubleValue());
        }

        //Put the smoothed data back in values
        int cntr = 0;
        int gyrCounter = 0;
        int accCounter = 0;
        while (cntr < values.size() - 1)
        {
            for (int i = 0; i < 3; i++, cntr++, accCounter++) {
                values.set(cntr, normAcc.get(accCounter));
            }
            for (int i = 0; i < 3; i++, cntr++, gyrCounter++) {
                values.set(cntr, normGyro.get(gyrCounter));
            }
        }
*/
        //Fill instance with the values
        for (int i = 0; i < values.size(); i++)
        {
            instance.setValue(i, values.get(i));
        }

        ArrayList<Attribute> attributes = new ArrayList<>();
        for (int i = 1; i < 21; i++){
            attributes.add(new Attribute("AccX" + i));
            attributes.add(new Attribute("AccY" + i));
            attributes.add(new Attribute("AccZ" + i));
            attributes.add(new Attribute("GyrX" + i));
            attributes.add(new Attribute("GyrY" + i));
            attributes.add(new Attribute("GyrZ" + i));
        }
        // pay attention to the order of the gestures that should match your training file
        ArrayList<String> classValues = new ArrayList<>();
        classValues.add("Down");
        classValues.add("Left");
        classValues.add("Right");
        classValues.add("TiltLeft");
        classValues.add("TiltRight");
        classValues.add("Up");
        attributes.add(new Attribute("gesture", classValues));

        // now create the instances
        Instances unlabeled = new Instances("testData",attributes,120);

        // and here you should add your DenseInstance to the instances
        unlabeled.setClassIndex(unlabeled.numAttributes() - 1);

        //	Instances unlabeled = new Instances(test);
        unlabeled.add(instance);
        double clsLabel = 0;
        try {
            clsLabel = mTree.classifyInstance(unlabeled.instance(0));
        } catch (Exception e) {
            e.printStackTrace();
        }
        unlabeled.instance(0).setClassValue(clsLabel);

        bt_output.append(unlabeled.lastInstance().toString(120) + "\n"); //Label is found in last spot


        if (!isUnlocked)
        {
            if (unlabeled.lastInstance().toString(120).equals("TiltLeft"))
            {
                BROKER_URL = "tcp://m14.cloudmqtt.com:16303";
                connectToMqtt("knftdzxy", "GkpGmebv6Tk7");

                Unlock();

            }
            if (unlabeled.lastInstance().toString(120).equals("TiltRight"))
            {
                BROKER_URL = "tcp://m23.cloudmqtt.com:13735";
                connectToMqtt("fxbddagl", "eKoK_ydFItcL");

                Unlock();
            }
        }


        else if(isUnlocked)
        {
            publishGestureToMqtt(unlabeled.lastInstance().toString(120));
        }


        normAcc.clear();
        normGyro.clear();
        values.clear();
    }



    private void ConnectToBluetooth()
    {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            // Device doesn't support Bluetooth
        }

        if (!mBluetoothAdapter.isEnabled()) {
            //Prompt to turn on BT
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        BluetoothDevice mDevice = null;
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                mDevice = device;
            }
        }

        ConnectThread mConnectThread = new ConnectThread(mDevice);
        mConnectThread.start();

    }

    private MqttConnectOptions getMqttConnectionOption(String username, String password) {
        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setCleanSession(false);
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setUserName(username);
        mqttConnectOptions.setPassword(password.toCharArray());
        return mqttConnectOptions;
    }
    private DisconnectedBufferOptions getDisconnectedBufferOptions() {
        DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
        disconnectedBufferOptions.setBufferEnabled(true);
        disconnectedBufferOptions.setBufferSize(100);
        disconnectedBufferOptions.setPersistBuffer(true);
        disconnectedBufferOptions.setDeleteOldestMessages(false);
        return disconnectedBufferOptions;
    }

    public MqttAndroidClient getMqttClient(Context context, String brokerUrl, String clientId, String username, String password) {
        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), brokerUrl, clientId);
        try {
            IMqttToken token = mqttAndroidClient.connect(getMqttConnectionOption(username, password));
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    mqttAndroidClient.setBufferOpts(getDisconnectedBufferOptions());
                    Log.d("MainActivity", "Success!");
                    publishGestureToMqtt("X"); //Tell arduino that we have connected
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.d("MainActivity", "Failure " + exception.toString());
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
        return mqttAndroidClient;
    }

    public void publishMessage(@NonNull MqttAndroidClient client,
                               @NonNull String msg, int qos, @NonNull String topic)
            throws MqttException, UnsupportedEncodingException {
        byte[] encodedPayload = new byte[0];
        encodedPayload = msg.getBytes("UTF-8");
        MqttMessage message = new MqttMessage(encodedPayload);
        message.setId(5866);
        message.setRetained(true);
        message.setQos(qos);
        client.publish(topic, message);
    }


    public void publishGestureToMqtt(String gesture)
    {
        try {
            publishMessage(client, gesture, 0, "Gesture");
        } catch (MqttException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    public void connectToMqtt(String username, String password)
    {
        //Client for the mqqt cloud
        client = getMqttClient(getApplicationContext(), BROKER_URL, "androidkt", username, password);
    }

    public void Unlock()
    {
        isUnlocked = true;

    }

}

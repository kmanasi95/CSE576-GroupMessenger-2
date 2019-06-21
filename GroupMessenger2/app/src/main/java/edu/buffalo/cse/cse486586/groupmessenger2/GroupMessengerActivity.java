package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

//Implemented ISIS algorithm for Total ordering by referring course slides and some inputs from the TA

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 * 
 * @author stevko
 *
 */

public class GroupMessengerActivity extends Activity {
    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    static final String[] ARRAY_OF_REMOTE_PORTS = {"11108","11112","11116","11120","11124"};
    static final int SERVER_PORT = 10000;
    private static int key = 0;
    private int proposedPriority = 0;
    private static float agreedPriority = 0.0f;
    private static int failedProcessId = 0;

    LinkedList<Float> proposedPriorities = new LinkedList<Float>();
    PriorityQueue<MessageTask>  deliveryQueue = new PriorityQueue<MessageTask>(100, MessageTask.CompareSequenceNumbers);
    final Lock lock = new ReentrantLock();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

         /* TODO: Use the TextView to display your messages. Though there is no grading component
         * on how you display the messages, if you implement it, it'll make your debugging easier.
         */

        TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());

        //Referred from PA1
        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        final String myPort = String.valueOf(Integer.parseInt(portStr) * 2);

        try{
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket");
        }

         /* Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */

        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));
        

        /*
         * TODO: You need to register and implement an OnClickListener for the "Send" button.
         * In your implementation you need to get the message from the input box (EditText)
         * and send it to other AVDs.
         */

        final EditText editText = (EditText) findViewById(R.id.editText1);

        //Retrieve pointer for send button
        final Button sendButton = (Button) findViewById(R.id.button4);

        //Register actionlistener for send button
        sendButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                String msg = editText.getText().toString() + "\n";
                editText.setText(""); // This is one way to reset the input box.
                TextView textView = (TextView) findViewById(R.id.textView1);
                textView.append("\t" + msg); // This is one way to display a string.

                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
            }
        });
    }


    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        //Referred from OnPTestClickListener.java
        private Uri buildUri(String scheme, String authority) {
            Uri.Builder uriBuilder = new Uri.Builder();
            uriBuilder.authority(authority);
            uriBuilder.scheme(scheme);
            return uriBuilder.build();
        }

        @Override
        protected Void doInBackground(ServerSocket... serverSockets) {
            ServerSocket serverSocket = serverSockets[0];

            while(true) {
                try {
                    Socket socketForServer = serverSocket.accept();
                    DataInputStream inputMessage = new DataInputStream(socketForServer.getInputStream());
                    String str = inputMessage.readUTF();
                    String[] splitMessage = str.split(",");
                    failedProcessId = Integer.parseInt(splitMessage[2]);
                    //Log.i(TAG, "From client : " + str);

                    //If failed port is found, only delete the messages related to that port from the queue which are not deliverable
                    if (failedProcessId != 0) {
                        Log.i(TAG, "Failed process : " + failedProcessId);
                        for (MessageTask m : deliveryQueue) {
                            if(!m.deliveryStatus) {
                                if (Integer.parseInt(m.processId) == failedProcessId) {
                                    Log.i(TAG, "Deleting message .............. " + m.message + " " + m.processId);
                                    deliveryQueue.remove(m);
                                }
                            }
                        }
                    }

                    //The if part executes when Client sends the agreed sequence number
                    if(str.contains("AgreedPriority")) {
                        MessageTask mTask = new MessageTask();
                        mTask.message = splitMessage[0];
                        mTask.processId = splitMessage[1];
                        mTask.failedProcessId = Integer.parseInt(splitMessage[2]);
                        mTask.proposedSequenceNumber = Math.round(Float.parseFloat(splitMessage[3]));

                        Log.i(TAG, "From client, message: " + mTask.message);
                        Log.i(TAG, "From client, processId : " + mTask.processId);
                        Log.i(TAG, "From client, seqNo : " + mTask.proposedSequenceNumber);
                        Log.i(TAG, "From client, failed process : " + mTask.failedProcessId);

                        /*Updating the proposed sequence number, which will be greater than maximum of previously proposed
                        sequence number and the agreed sequence number*/
                        proposedPriority = Math.max(mTask.proposedSequenceNumber, proposedPriority) + 1;

                        /*if (failedProcessId != 0) {
                            Log.i(TAG, "Failed process : " + failedProcessId);
                            for (MessageTask m : deliveryQueue) {
                                if(!m.deliveryStatus) {
                                    if (Integer.parseInt(m.processId) == failedProcessId) {
                                        Log.i(TAG, "Deleting message .............. " + m.message + " " + m.processId);
                                        deliveryQueue.remove(m);
                                    }
                                }
                            }
                        }*/

                        mTask.deliveryStatus = true;

                        //Reorder the priority queue with agreed sequence number
                        synchronized (this) {
                            for (MessageTask m : deliveryQueue) {
                                if (m.processId.equals(mTask.processId) && m.message.equals(mTask.message)) {
                                    deliveryQueue.remove(m);
                                    m.message = mTask.message;
                                    m.processId = mTask.processId;
                                    m.proposedSequenceNumber = mTask.proposedSequenceNumber;
                                    m.deliveryStatus = mTask.deliveryStatus;
                                    deliveryQueue.add(m);

                                    break;
                                }
                            }
                        }

                        DataOutputStream outputMessage = new DataOutputStream(socketForServer.getOutputStream());
                        outputMessage.writeUTF("Delivered");

                        outputMessage.flush();
                        outputMessage.close();
                        socketForServer.close();
                    }
                    else {
                        MessageTask mTask = new MessageTask();
                        mTask.message = splitMessage[0];
                        mTask.processId = splitMessage[1];
                        mTask.failedProcessId = Integer.parseInt(splitMessage[2]);

                        //Log.i(TAG, "From client, message : " + mTask.message);
                        //Log.i(TAG, "From client, processId: " + mTask.processId);
                        //Log.i(TAG, "From client, failed process : " + mTask.failedProcessId);

                        Log.i(TAG, "Previous proposal at sender : " + proposedPriority);

                        lock.lock();
                        try{
                            proposedPriority++;

                            mTask.proposedSequenceNumber = proposedPriority;
                            mTask.deliveryStatus = false;

                            deliveryQueue.add(mTask);
                        } finally {
                            lock.unlock();
                        }

                        //Log.i(TAG, "Receiver proposed : " + proposedPriority);


                        DataOutputStream outputMessage = new DataOutputStream(socketForServer.getOutputStream());
                        outputMessage.writeUTF(mTask.message + "," + mTask.processId + "," + mTask.proposedSequenceNumber + "." + mTask.processId + "," + mTask.deliveryStatus);
                        outputMessage.flush();

                        Log.i(TAG, "To client : " + mTask.message + "," + mTask.processId + "," + mTask.proposedSequenceNumber + "." + mTask.processId + "," + mTask.deliveryStatus);

                        outputMessage.close();
                        socketForServer.close();

                    }

                    /*for(MessageTask m : deliveryQueue){
                        if(m.deliveryStatus) {
                            Log.i(TAG, "DeliveryQueue contents : " + m.message+" " +m.proposedSequenceNumber);
                        }
                    }*/

                    //Deliver all those messages at the start of the queue which are deliverable
                    synchronized (this){
                        MessageTask head = deliveryQueue.peek();
                        while (head != null && head.deliveryStatus) {
                            MessageTask dMessage = deliveryQueue.poll();
                            Log.i(TAG, "Delivered message : " + dMessage.message + " " + dMessage.proposedSequenceNumber);
                            publishProgress(dMessage.message);
                            head = deliveryQueue.peek();

                            if(head == null)
                                break;
                        }
                    }

                } catch (IOException e) {
                    Log.e(TAG, "ServerTask socket IOException");
                }
            }
        }

        protected void onProgressUpdate(String...strings) {
            String strReceived = strings[0].trim();
            TextView textView = (TextView) findViewById(R.id.textView1);
            textView.append(strReceived + "\t\n");

            //Reference : https://developer.android.com/reference/android/content/Context.html#getContentResolver()
            ContentResolver contentResolver = getContentResolver();

            //Referred from OnPTestClickListener.java
            Uri mUri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
            ContentValues contentValues = new ContentValues();
            contentValues.put("key",Integer.toString(key));
            contentValues.put("value", strReceived);
            contentResolver.insert(mUri, contentValues);
            key++;
        }
    }


    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {
            try {
                String msgToSend = msgs[0];

                //Multicasting messages on all 5 AVDs
                for (int i=0; i<ARRAY_OF_REMOTE_PORTS.length; i++) {
                    try {
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]));

                        DataOutputStream outputMessage = new DataOutputStream(socket.getOutputStream());
                        outputMessage.writeUTF(msgToSend + "," + msgs[1] + "," + failedProcessId);
                        Log.i(TAG, "Process id " + msgs[1]);
                        outputMessage.flush();

                        DataInputStream inputMessage = new DataInputStream(socket.getInputStream());
                        String str = inputMessage.readUTF();
                        String[] splitMessage = str.split(",");
                        MessageTask mTask = new MessageTask();
                        mTask.message = splitMessage[0];
                        mTask.processId = splitMessage[1];
                        agreedPriority = Float.parseFloat(splitMessage[2]);
                        mTask.deliveryStatus = Boolean.parseBoolean(splitMessage[3]);

                        //Storing proposals from all AVDs into a list
                        proposedPriorities.add(agreedPriority);

                        outputMessage.close();
                        socket.close();
                    } catch (StreamCorruptedException sc){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask Stream Corrupted Exception" + sc.getMessage());
                    } catch (FileNotFoundException fe){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask File Not Found Exception" + fe.getMessage());
                    } catch (NullPointerException np){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask ENull Pointer Exception" + np.getMessage());
                    } catch (EOFException eo){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask EOFException" + eo.getMessage());
                    } catch (IOException ie) {
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask IOException" + ie.getMessage());
                    } catch (Exception e) {
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask Exception" + e.getMessage());
                    }
                }

                //Calculating agreed sequence, which is the maximum of all proposed sequence numbers
                agreedPriority = Collections.max(proposedPriorities);
                Log.i(TAG, "Client agreed on : " + agreedPriority);

                //Now, multicast messages on all 5 AVDs with the agreed sequence number
                for(int i=0; i<ARRAY_OF_REMOTE_PORTS.length;i++){
                    try{
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]));

                        DataOutputStream outputMessage = new DataOutputStream(socket.getOutputStream());
                        outputMessage.writeUTF(msgToSend + "," + msgs[1] + "," + failedProcessId + "," + agreedPriority + "," + "AgreedPriority");
                        outputMessage.flush();

                        DataInputStream inputMessage = new DataInputStream(socket.getInputStream());
                        String str = inputMessage.readUTF();
                        if (str.contains("Delivered")) {
                            outputMessage.close();
                            socket.close();
                        }

                    } catch (StreamCorruptedException sc){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask Stream Corrupted Exception" + sc.getMessage());
                    } catch (FileNotFoundException fe){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask File Not Found Exception" + fe.getMessage());
                    } catch (NullPointerException np){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask Null Pointer Exception" + np.getMessage());
                    } catch (EOFException eo){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask EOFException" + eo.getMessage());
                    } catch (IOException e){
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask IOException" + e.getMessage());
                    } catch (Exception e) {
                        failedProcessId = Integer.parseInt(ARRAY_OF_REMOTE_PORTS[i]);
                        Log.i(TAG, "Failed process : "+failedProcessId);
                        Log.e(TAG, "ClientTask Exception" + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "ClientTask Exception" + e.getMessage());
            }

            return null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }

    public static class MessageTask{
        String message;
        String processId;
        int proposedSequenceNumber;
        boolean deliveryStatus;
        int failedProcessId;

        public static Comparator<MessageTask> CompareSequenceNumbers = new Comparator<MessageTask>() {
            @Override
            public int compare(MessageTask lhs, MessageTask rhs) {
                if(lhs.proposedSequenceNumber > rhs.proposedSequenceNumber){
                    return 1;
                }
                else if(lhs.proposedSequenceNumber == rhs.proposedSequenceNumber){
                    if(Integer.parseInt(lhs.processId) > Integer.parseInt(rhs.processId))
                        return 1;
                    else
                        return -1;
                }
                else if(lhs.proposedSequenceNumber < rhs.proposedSequenceNumber){
                    return -1;
                }
                return  0;
               }
        };
    }
}
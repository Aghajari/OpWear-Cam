# OpWear-Cam
 Communicating between Wear OS and Android device using the OpWear module and a sample of displaying real-time camera on the watch and sending commands to the mobile phone by Wear OS.

<img src="./Images/wear.png" width=200/>

## Usage

```kotlin
class MainActivity : AppCompatActivity(),
    OpWear.OnConnectionChangedListener,
    MessageClient.OnMessageReceivedListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ***
        
        OpWear.install(this)
        
        // ONLY OBSERVER SHOULD CALL THIS
        lifecycleScope.launch { OpWear.connect() }
    }

    override fun onConnectionChange(status: OpWear.ConnectionStatus) {
        println(status.name)

        if (status == OpWear.ConnectionStatus.CONNECTED) {
            lifecycleScope.launch {
                OpWear.sendMessage(
                    "Hello",
                    "Hello ${OpWear.connectedNodeDisplayName}!".toByteArray()
                )
            }
        }
    }

    override fun onMessageReceived(msg: MessageEvent) {
        println("MESSAGE RECEIVED:")
        println(msg.path)
        println(String(msg.data))
    }
}
```

We consider one device as observer and another as observable. In sample, mobile is the observer. 
The only difference is that the Observer must call ‍`OpWear.connect()`, while the Observable can accept or reject the Observer's request.

By default every request is accepted by observable. You can implement `OpWear.OnAcknowledgeListener` to manage requests.
```kotlin
OpWear.acknowledgeListener = OpWear.OnAcknowledgeListener { nodeId, displayName ->
    println("$displayName:$nodeId WANTS TO CONNECT")
    true
}
```

By calling `OpWear.install(context)`, If the context is an instance of `LifecycleOwner`, everything will be handled, but otherwise you have to call

```kotlin
OpWear.attachToLifecycle(owner)
```

OR

```kotlin
override fun onResume() {
    super.onResume()
    OpWear.register()
}

override fun onPause() {
    super.onPause()
    OpWear.unregister()
}
```

### AutoValidation
Auto validation will make sure that connection is alive.

- **RESPONSE:** Must receive a message within the specified time. Useful for observable real-time connections.
- **ACKNOWLEDGE:** Sends acknowledgment request at the specified time interval.

```kotlin
OpWear.autoValidationStrategy = OpWear.AutoValidationStrategy.NONE
OpWear.autoValidationStrategy = OpWear.AutoValidationStrategy.RESPONSE
OpWear.autoValidationStrategy = OpWear.AutoValidationStrategy.ACKNOWLEDGE
```


License
=======

    Copyright 2022 Amir Hossein Aghajari
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


<br>
<div align="center">
  <img width="64" alt="LCoders | AmirHosseinAghajari" src="https://user-images.githubusercontent.com/30867537/90538314-a0a79200-e193-11ea-8d90-0a3576e28a18.png">
  <br><a>Amir Hossein Aghajari</a> • <a href="mailto:amirhossein.aghajari.82@gmail.com">Email</a> • <a href="https://github.com/Aghajari">GitHub</a>
</div>

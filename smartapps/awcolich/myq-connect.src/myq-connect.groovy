definition(
	name: "MyQ (Connect)",
	namespace: "awcolich",
	author: "Tony Colich",
	description: "Connect MyQ to control your devices",
	category: "SmartThings Labs",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Meta/garage_outlet.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Meta/garage_outlet@2x.png"
)

preferences {
	page(name: "prefLogIn", title: "MyQ")
	page(name: "prefListDevices", title: "MyQ")
}

def prefLogIn() {
	try {
        def showUninstall = username != null && password != null
        
        return dynamicPage(name: "prefLogIn", title: "Connect to MyQ", nextPage:"prefListDevices", uninstall:showUninstall, install: false) {
            section("Login Credentials") {
                input("username", "email", title: "Username", description: "MyQ Username (email address)")
                input("password", "password", title: "Password", description: "MyQ password")
            }
            section("Gateway Brand") {
                input(name: "brand", title: "Gateway Brand", type: "enum", metadata:[values:["Liftmaster", "Chamberlain", "Craftsman"]])
            }
        }
    }
    catch (e) {
    	log.error("prefLogIn Failed", e)
    }
}

def prefListDevices() {
	try {    
        if (forceLogin()) {
        	def allDevices = getDeviceList()
            
            def doorList = allDevices?.findAll { it.contains("GarageDoorOpener") }
            
            if (doorList) {
                return dynamicPage(name: "prefListDevices", title: "Devices", install:true, uninstall:true) {
                    section("Select which garage door/gate to use") {
                        input(name: "doors", type: "enum", required:false, multiple:false, metadata:[values:doorList])
                    }
                    section("Send Push Notification?") {
                        input("sendPush", "bool", required: false, title: "Send Push Notification?")
                    }
                }
            } 
            else {
                return dynamicPage(name: "prefListDevices", title: "Error!", install:true, uninstall:true) {
                    section("") {
                        paragraph "Could not find any supported device(s). Please report to author about these devices: $allDevices"
                    }
                }
            }
        } 
        else {
            return dynamicPage(name: "prefListDevices", title: "Error!", install:false, uninstall:true) {
                section("") {
                    paragraph "The username or password you entered is incorrect. Try again."
                }
            }
        }
    }
    catch (e) {
    	log.error("prefListDevices", e)
    }
}

def installed() { 
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def uninstalled() {
	unschedule()
	getAllChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}	

def initialize() {
	try {
		login()

		state.polling = [ last: 0, rescheduler: now() ]
		
		def selectedDevices = [] + settings.get("doors")
		
        selectedDevices.each { 
        	if (it?.contains("GarageDoorOpener") && !getChildDevice(it)) {
        		addChildDevice("awcolich", "MyQ Garage Door Opener", it.substring(0, it.lastIndexOf('|')), null, ["name": "MyQ: ${it.substring(it.lastIndexOf('|'))}"]) 
            }
        }

		def deleteDevices = getAllChildDevices().each { 
        	if (!selectedDevices.contains(it.deviceNetworkId)) deleteChildDevice(it.deviceNetworkId) 
        }

		runRefresh()
	}
	catch (e) {
		log.error("MyQApp: initialize() Failed", e)
	}
}

private forceLogin() {
	try {
        state.session = [ brandID: 0, brandName: settings.brand, securityToken: null, expiration: 0 ]
        state.polling = [ last: 0, rescheduler: now() ]

		return doLogin()
    }
    catch (e) {
    	log.error("MyQApp: forceLogin() Failed", e)
    }
}

private login() { 
	try {
		if (!(state.session?.expiration > now())) return doLogin()
        else return true
    }
    catch (e) {
    	log.error("MyQApp: login() Failed", e)
    }
}

private doLogin() {
	try {
        apiPost("/api/v4/user/validate", "{\"username\": \"${settings.username}\", \"password\": \"${settings.password}\"}") { response ->
        	if (response.status == 200 && response.data.ReturnCode == "0") {			
                state.session.securityToken = response.data.SecurityToken
                state.session.expiration = now() + 150000
				
                return true
            }
            else {
            	log.error("MyQApp: Logon Failed: ${response.data}")
                return false
            }
        }
    }
    catch (e) {
    	log.error("MyQApp: doLogin() Failed", e)
    }
}

private getDeviceList() {
	try {
        def deviceList = [:]
        
        apiGet("/api/v4/userdevicedetails/get", []) { response ->
        	if (response.status == 200 && response.data.ReturnCode == "0") {
                response.data.Devices.each { 
                	def deviceType = ""
                    if (it.MyQDeviceTypeId == 2 || it.MyQDeviceTypeId == 5 || it.MyQDeviceTypeId == 7 || it.MyQDeviceTypeId == 17) {
                    	deviceType = "GarageDoorOpener"
                    }
                    else if (it.MyQDeviceTypeId == 3) {
                    	deviceType = "LightController"
                    }
                        
                    if (deviceType != "") {
                    	def deviceName = it.Attributes.find { it.AttributeDisplayName == "desc" }
                        
                        if (deviceName) {
                            deviceList.add([app.id, deviceType, it.MyQDeviceId, deviceName.Value].join('|'))
                        }
                        else log.info "Device Name Not Found!!!"
                    }
                }
            }
            else {
            	log.error("MyQApp: Get Device List Failed: ${response.data}")
            }
        }
        
        return deviceList        
    }
    catch (e) {
    	log.error("MyQApp: getDeviceList() Failed", e)
    }        
}

private getApiURL() {
	return settings.brand == "Craftsman" ? "https://craftexternal.myqdevice.com" : "https://myqexternal.myqdevice.com"
}

private getApiAppID() {
	return settings.brand == "Craftsman" ? "QH5AzY8MurrilYsbcG1f6eMTffMCm3cIEyZaSdK/TD/8SvlKAWUAmodIqa5VqVAs" : "NWknvuBd7LoFHfXmKNMBcgajXtZEgKUh4V7WNzMidrpUUluDpVYVZx+xT4PCM5Kx"
}
	
private apiGet(apiPath, apiQuery = [], callback = {}) {	
	try {
        //log.info """MyQApp: GET Info:
        //    Url = ${getApiURL()}
        //    Path = ${apiPath}
        //    MyQApplicationId = ${getApiAppID()}
        //    SecurityToken = ${state.session.securityToken}
        //    Query = ${apiQuery.toString()}
        //    """
            
        def params = [
            uri: getApiURL(),
            path: apiPath,
            query: apiQuery,
            headers: [ "MyQApplicationId": getApiAppID(), "SecurityToken": state.session.securityToken, "Content-Type": "application/json" ]
        ]

		httpGet(params) { resp -> callback(resp) }
	}
	catch (e) {
		log.error("MyQApp: apiGet() Failed", e)
	}
}

private apiPost(apiPath, apiBody, callback = {}) {
    try {
	    //log.info """MyQApp: GET Info:
        //    Url = ${getApiURL()}
        //    Path = ${apiPath}
        //    MyQApplicationId = ${getApiAppID()}
        //    SecurityToken = ${state.session.securityToken}
        //    """
            
        def params = [
            uri: getApiURL(),
            path: apiPath,
            headers: [ "MyQApplicationId": getApiAppID(), "Content-Type": "application/json" ],
            body: apiBody
        ]

		httpPostJson(params) { resp -> callback(resp) }
	}
	catch (e) {
		log.error("MyQApp: apiPost() Failed", e)
	}
}

private apiPut(apiPath, apiBody, callback = {}) {
	try {    
    	//log.info """MyQApp: GET Info:
        //    Url = ${getApiURL()}
        //    Path = ${apiPath}
        //    MyQApplicationId = ${getApiAppID()}
        //    SecurityToken = ${state.session.securityToken}
        //    """
            
        def params = [ 
            uri: getApiURL(), 
            path: apiPath,
            body: apiBody,
            headers: [ "MyQApplicationId": getApiAppID(), "SecurityToken": state.session.securityToken, "Content-Type": "application/json" ]
        ]
        
		httpPut(params) { resp -> callback(resp) }
	} 
    catch (SocketException e)	{
		log.error("MyQApp: apiPut() Failed", e)
	}
}

def refresh() {
	try {
        getAllChildDevices().each {
        	def devStatus = getDeviceStatus(it)
            
            it.updateDeviceStatus(devStatus.AttributeValue)

            if (it.deviceNetworkId.contains("GarageDoorOpener")) {
                it.updateDeviceLastActivity(devStatus.Updated)
            }
        }
        
        if ((state.polling["rescheduler"]?:0) + 2400000 < now()) {
            runEvery30Minutes(runRefresh)
            state.polling["rescheduler"] = now()
        }
    }
    catch (e) {
    	log.error("MyQApp: refresh() Failed", e)
    }
}

def getChildDeviceID(child) {
	try {
		return child.device.deviceNetworkId.split("\\|")[2]
    }
    catch (e) {
    	log.error("MyQApp: getChildDeviceID() Failed", e)
    }
}

def getDeviceStatus(child) {
	try {
    	def query = [ "MyQDeviceId": "${child.device.deviceNetworkId.split("\\|")[2]}", "AttributeName": "doorstate" ]
		apiGet("/api/v4/deviceattribute/getdeviceattribute", query) { response -> 
        	if (response.status == 200 && response.data.ReturnCode == "0") {
                return response.data
            }
            else {
                log.error("apiPut returned error: ${response.data.ReturnCode}-${response.data.ErrorMessage}")
                return null
            }
        }
    }
    catch (e) {
    	log.error("MyQApp: getDeviceStatus() Failed", e)
    }
}

/*def getDeviceLastActivity(child) {
	try {
		return state.data[child.device.deviceNetworkId].lastAction.toLong()
    }
    catch (e) {
    	log.error("MyQApp: getDeviceLastActivity() Failed", e)
    }
}*/

def sendCommand(child, attributeName, attributeValue) {
	try {
        if (login()) {
        	def apiBody = "{ \"MyQDeviceId\": \"${getChildDeviceID(child)}\", \"AttributeName\": \"${attributeName}\", \"AttributeValue\": \"${attributeValue}\"}"
            
            apiPut("/api/v4/deviceattribute/putdeviceattribute", apiBody) { response -> 
            	if (response.status == 200 && response.data.ReturnCode == "0") {
                	return true                
                }
                else {
                	log.error("apiPut returned error: ${response.data.ReturnCode}-${response.data.ErrorMessage}")
                    return false
                }
            }
        }
    }
    catch (e) {
    	log.error("MyQApp: sendCommand() Failed", e)
    }
}

def runRefresh(evt) {
	try {
        if (evt) runIn(30, refresh)
        if ((((state.polling["last"]?:0) + 600000) < now()) && canSchedule()) schedule("* */5 * * * ?", refresh)

        refresh()

        if (!evt) state.polling["rescheduler"] = now()
    }
    catch (e) {
    	log.error("MyQApp: runRefresh() Failed", e)
    }
}
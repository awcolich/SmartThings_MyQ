metadata {
	definition (name: "MyQ Garage Door Opener", namespace: "awcolich", author: "Tony Colich") {
		capability "Garage Door Control"
		capability "Door Control"
		capability "Contact Sensor"
		capability "Refresh"
		capability "Polling"

		capability "Actuator"
		capability "Switch"
		capability "Momentary"
		capability "Sensor"
		
		attribute "lastActivity", "string"
		command "updateDeviceStatus", ["string"]
		command "updateDeviceLastActivity", ["number"]
	}

	simulator {	}

	tiles {
		standardTile("door", "device.door", width: 2, height: 2) {
			state("unknown", label:'${name}', action:"door control.close", icon:"st.doors.garage.garage-open", backgroundColor:"#ffa81e", nextState: "closing")
			state("closed", label:'${name}', action:"door control.open", icon:"st.doors.garage.garage-closed", backgroundColor:"#79b821", nextState: "opening")
			state("open", label:'${name}', action:"door control.close", icon:"st.doors.garage.garage-open", backgroundColor:"#ffa81e", nextState: "closing")
			state("opening", label:'${name}', icon:"st.doors.garage.garage-opening", backgroundColor:"#ffe71e", nextState: "open")
			state("closing", label:'${name}', icon:"st.doors.garage.garage-closing", backgroundColor:"#ffe71e", nextState: "closed")
			state("stopped", label:'stopped', action:"door control.close",  icon:"st.doors.garage.garage-opening", backgroundColor:"#1ee3ff", nextState: "closing")
		}
		standardTile("refresh", "device.door", inactiveLabel: false, decoration: "flat") {
			state("default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh")
		}
		valueTile("lastActivity", "device.lastActivity", inactiveLabel: false, decoration: "flat") {
			state "default", label:'Last activity: ${currentValue}', action:"refresh.refresh", backgroundColor:"#ffffff"
		}

		main "door"
		details(["door", "lastActivity", "refresh"])
	}
}

def parse(String description) {}

def on() {
	try {
    	push() 
        sendEvent(name: "button", value: "on", isStateChange: true, display: false, displayed: false)
    }
    catch (e) {
    	log.error("MyQ: on() Failed", e)
    }
}

def off() { 
	try {
    	sendEvent(name: "button", value: "off", isStateChange: true, display: false, displayed: false)
    }
    catch (e) {
    	log.error("MyQ: off() Failed", e)
    }
}

def push() { 
	try {
    	def doorState = device.currentState("door")?.value

        if (doorState == "open" || doorState == "stopped") close()
        else if (doorState == "closed") open()

        sendEvent(name: "momentary", value: "pushed", display: false, displayed: false)
    }
    catch (e) {
    	log.error("MyQ: push() Failed", e)
    }
}

def open()  { 
	try {
        if (device.currentState("door")?.value == "closed") {
            parent.sendCommand(this, "desireddoorstate", 1)
            
            def totalSleep = 0
            sleepForDuration(7500) { totalSleep += it }
			
            def currentStatus = translateDoorStatus(parent.getDeviceStatus(this)?.AttributeValue, "closed")
            
			while (currentStatus == "opening" && totalSleep <= 10000)
            {
                sleepForDuration(1000) {
                    dTotalSleep += it
                    currentStatus = translateDoorStatus(parent.getDeviceStatus(this)?.AttributeValue, "closed")
                }
            }
            
            updateDeviceStatus()
        }
    }
    catch (e) {
    	log.error("MyQ: open() Failed", e)
    }
}

def close() { 
	try {
        if (device.currentState("door")?.value == "open") {
            parent.sendCommand(this, "desireddoorstate", 0) 
            
            def totalSleep = 0
            sleepForDuration(7500) { totalSleep += it }
			
            def currentStatus = translateDoorStatus(parent.getDeviceStatus(this)?.AttributeValue, "open")
            
			while (currentStatus == "closing" && totalSleep <= 10000)
            {
                sleepForDuration(1000) {
                    dTotalSleep += it
                    currentStatus = translateDoorStatus(parent.getDeviceStatus(this)?.AttributeValue, "open")
                }
            }
            
            def newStatus = parent.getDeviceStatus(this)
            updateDeviceStatus(newStatus.AttributeValue)
            updateDeviceLastActivity(newStatus.Updated)
        }
    }
    catch (e) {
    	log.error("MyQ: close() Failed", e)
    }
}

def refresh() {
	try {
        parent.refresh()
    }
    catch (e) {
    	log.error("MyQ: refresh() Failed", e)
    }
}

def poll() { 
	try {
    	refresh()
    }
    catch (e) {
    	log.error("MyQ: poll() Failed", e)
    }
}

def updateDeviceStatus() {
	try {
    	def statusFromParent = parent.getDeviceStatus(this)
        
        log.debug "Status From Parent: ${statusFromParent}"
        
        def translatedState = translateDoorStatus(statusFromParent, device.currentState("door").value)
        sendEvent(name: "door", value: translatedState, display: true, descriptionText: "${device.displayName} is ${translatedState}")
        
        updateDeviceLastActivity(newStatus.Updated)
    }
    catch (e) {
    	log.error("MyQ: updateDeviceStatus() Failed", e)
    }
}

def updateDeviceLastActivity(long lastActivity) {
    try {
        def lastActivityValue = ""
        def diffTotal = now() - lastActivity       
        def diffDays  = (diffTotal / 86400000) as long
        def diffHours = (diffTotal % 86400000 / 3600000) as long
        def diffMins  = (diffTotal % 86400000 % 3600000 / 60000) as long

        if (diffDays == 1)  lastActivityValue += "${diffDays} Day"
        else if (diffDays > 1)   lastActivityValue += "${diffDays} Days"

        if (diffHours == 1) lastActivityValue += "${diffHours} Hour"
        else if (diffHours > 1)  lastActivityValue += "${diffHours} Hours"

        if (diffMins == 1 || diffMins == 0 ) lastActivityValue += "${diffMins} Min"
        else if (diffMins > 1) lastActivityValue += "${diffMins} Mins"    

        sendEvent(name: "lastActivity", value: lastActivityValue, display: false , displayed: false)
    }
    catch (e) {
    	log.error("MyQ: updateDeviceLastActivity() Failed", e)
    }
}

def sleepForDuration(duration, callback = {}) {
	def dTotalSleep = 0
	def dStart = new Date().getTime()
    
    while (dTotalSleep <= duration)
    {            
		try { httpGet("http://australia.gov.au/404") { } } catch (e) { }
        dTotalSleep = (new Date().getTime() - dStart)
    }

	callback(dTotalSleep)
}

def translateDoorStatus(status, initStatus = null) {
	if (status == "2") return "closed"
	else if (status == "1" || status == "9") return "open"
	else if (status == "4" || (status == "8" && initStatus == "closed")) return "opening"
	else if (status == "5" || (status == "8" && initStatus == "open")) return "closing"
    else if (status == "3") return "stopped"
    else if (status == "8" && initStatus == null) return "moving"
    else { 
    	log.debug "Unknown Door Status ID: $status" 
        return null
    }
}

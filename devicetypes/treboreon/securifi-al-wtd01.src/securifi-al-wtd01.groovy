metadata {
	definition (name: "Securifi AL-WTD01", namespace: "treboreon", author: "treboreon") {
		capability "Water Sensor"
		capability "Sensor"
        capability "Configuration"
        //capability "Battery"
        
        attribute "tamperSwitch","ENUM",["open","closed"]
                
        command "enrollResponse"
//        command "getClusters"
        
		fingerprint endpointId: '08', profileId: '0104', inClusters: "0000,0003,0500", outClusters: "0003"
	}

	// simulator metadata
	simulator {
		status "active": "zone report :: type: 19 value: 0031"
		status "inactive": "zone report :: type: 19 value: 0030"
	}

	// UI tile definitions
	tiles(scale: 2) {
    multiAttributeTile(name:"water", type: "generic", width: 6, height: 4){
       tileAttribute ("device.water", key: "PRIMARY_CONTROL") {
            attributeState "dry", label:'${name}', icon:"st.alarm.water.dry", backgroundColor:"#79b821"
            attributeState "wet", label:'${name}', icon:"st.alarm.water.wet", backgroundColor:"#53a7c0"
        }
    }    
        standardTile("water", "device.water", width: 2, height: 2) {
 		   state "dry", label:'${name}', icon:"st.alarm.water.dry", backgroundColor:"#79b821"
    	   state "wet", label:'${name}', icon:"st.alarm.water.wet", backgroundColor:"#53a7c0"
		}
        
        standardTile("tamperSwitch", "device.tamperSwitch", width: 1, height: 1) {
			state("open", label:'${name}', icon:"st.contact.contact.open", backgroundColor:"#ffa81e")
			state("closed", label:'${name}', icon:"st.contact.contact.closed", backgroundColor:"#79b821")
		}        
		
        standardTile("configure", "device.configure", width: 1, height: 1) {
			state "configure", label:'', action:"getClusters", icon:"st.secondary.configure"
		}
        /*
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}*/
		main (["water"])
		//details(["water","tamperSwitch","configure", "battery"])
        details(["water","tamperSwitch"])
	}
}

def configure() {
	log.debug("** AL-WTD01 ** configure called for device with network ID ${device.deviceNetworkId}")
    
	String zigbeeId = swapEndianHex(device.hub.zigbeeId)
	log.debug "Configuring Reporting, IAS CIE, and Bindings."
	def configCmds = [
    	"zcl global write 0x500 0x10 0xf0 {${zigbeeId}}", "delay 200",
		"send 0x${device.deviceNetworkId} 1 1", "delay 1500",
        
        "zcl global send-me-a-report 1 0x20 0x20 0x3600 0x3600 {01}", "delay 200",
        "send 0x${device.deviceNetworkId} 1 1", "delay 1500",
        
		"zdo bind 0x${device.deviceNetworkId} 1 1 0x001 {${device.zigbeeId}} {}", "delay 1500",
        
        "raw 0x500 {01 23 00 00 00}", "delay 200",
        "send 0x${device.deviceNetworkId} 1 1", "delay 1500",
	]
    return configCmds // send refresh cmds as part of config
}

def getClusters() {
	log.debug "Get Clusters for ${device.deviceNetworkId}"
	"zdo active 0x${device.deviceNetworkId}"
}

def enrollResponse() {
	log.debug "Sending enroll response"
    [	
    	
	"raw 0x500 {01 23 00 00 00}", "delay 200",
    "send 0x${device.deviceNetworkId} 1 1"
        
    ]
}

// Parse incoming device messages to generate events
def parse(String description) {
	log.debug("** AL-WTD01 parse received ** ${description}")
    def result = []        
	Map map = [:]
    
    if (description?.startsWith('zone status')) {
	    map = parseIasMessage(description)
    }
// 	elseif (description?.startsWith('catchall:')) {
//		map = parseCatchAllMessage(description)
//	}
    
	log.debug "Parse returned $map"
    map.each { k, v ->
    	log.debug("sending event ${v}")
        sendEvent(v)
    }
    
//	def result = map ? createEvent(map) : null
    
    if (description?.startsWith('enroll request')) {
    	List cmds = enrollResponse()
        log.debug "enroll response: ${cmds}"
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
    }
    return result
}

// TESTING
/*
private Map parseCatchAllMessage(String description) {
	log.debug("** AL-WTD01 parse received ** CatchAllParse ")
    Map resultMap = [:]
    def cluster = zigbee.parse(description)
    if (shouldProcessMessage(cluster)) {
        switch(cluster.clusterId) {
            case 0x0001:
            	log.debug("Should get batter Result");
            	// resultMap = getBatteryResult(cluster.data.last())
                break

        }
    }

    return resultMap
}

private boolean shouldProcessMessage(cluster) {
    // 0x0B is default response indicating message got through
    // 0x07 is bind message
    boolean ignoredMessage = cluster.profileId != 0x0104 || 
        cluster.command == 0x0B ||
        cluster.command == 0x07 ||
        (cluster.data.size() > 0 && cluster.data.first() == 0x3e)
    return !ignoredMessage
}
*/ 

private Map parseIasMessage(String description) {
    List parsedMsg = description.split(' ')
    String msgCode = parsedMsg[2]
    
    Map resultMap = [:]
    switch(msgCode) {
        case '0x0030': // Closed/No Motion/Dry
            log.debug 'no motion'
            resultMap["motion"] = [name: "motion", value: "inactive"]
            resultMap["tamperSwitch"] = getContactResult("closed")            
            break

        case '0x0031': // Open/Motion/Wet
            log.debug 'motion'
            resultMap["motion"] = [name: "motion", value: "active"]
            resultMap["tamperSwitch"] = getContactResult("closed")            
            break

        case '0x0032': // Tamper Alarm
        	log.debug 'motion with tamper alarm'
            resultMap["motion"] = [name: "motion", value: "active"]
            resultMap["tamperSwitch"] = getContactResult("open")            
            break

        case '0x0034': // Supervision Report
        	log.debug 'no motion with tamper alarm'
            resultMap["motion"] = [name: "motion", value: "inactive"]
            resultMap["tamperSwitch"] = getContactResult("open")            
            break

        case '0x0035': // Restore Report
        	log.debug 'motion with tamper alarm'
            resultMap["motion"] = [name: "motion", value: "active"]
            resultMap["tamperSwitch"] = getContactResult("open") 
            break

        case '0x0036': // Trouble/Failure
        	log.debug 'msgCode 36 not handled yet'
            break

        case '0x0038': // Dry Mode
        	log.debug 'Dry with tamper alarm'
            resultMap["water"] = [name: "water", value: "dry"]
            resultMap["tamperSwitch"] = getContactResult("open") 
            break
            
        case '0x0039': // Wet Mode
        	log.debug 'Wet with tamper alarm'
            resultMap["water"] = [name: "water", value: "wet"]
            resultMap["tamperSwitch"] = getContactResult("open") 
            break            
    }
    return resultMap
}

private Map getContactResult(value) {
	log.debug "Tamper Switch Status ${value}"
	def linkText = getLinkText(device)
	def descriptionText = "${linkText} was ${value == 'open' ? 'opened' : 'closed'}"
	return [
		name: 'tamperSwitch',
		value: value,
		descriptionText: descriptionText
	]
}


private hex(value) {
	new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
    reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
    int i = 0;
    int j = array.length - 1;
    byte tmp;
    while (j > i) {
        tmp = array[j];
        array[j] = array[i];
        array[i] = tmp;
        j--;
        i++;
    }
    return array
}
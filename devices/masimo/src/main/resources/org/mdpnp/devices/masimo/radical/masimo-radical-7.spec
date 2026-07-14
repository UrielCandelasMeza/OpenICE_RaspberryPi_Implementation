^(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})\s+SN=(\S+)\s+CHAN=(\S+)\s+sysALARM=(\S+)\s+SPO2=(\S+?)%\s+BPM=(\S+)\s+PI=(\S+)\s+SPHB=(\S+)\s+SPOC=(\S+)\s+DESAT=(\S+)\s+PIDELTA=(\S+)\s+PVI=(\S+)\s+ALARM=(\S+)\s+ALARM1=(\S+)\s+ACSALARM=(\S+)\s+EXC=(\S+)\s+EXC1=(\S+)\s+EXC2=(\S+)\s+ACSEXC=(\S+)\s+eegPSI=(\S+)\s+eegEMG=(\S+?)%\s+eegSR=(\S+?)%\s+eegSEFL=(\S+?)Hz\s+eegSEFR=(\S+?)Hz\s+eegARTF=(\S+?)%\s+eegALARM=(\S+)\s+eegEXC=(\S+)\s*$
	firePulseOximeter
	lastPoint
		MM/dd/yy HH:mm:ss
	guid
	chan	filter
	sysAlarm	filter
	spo2	filter
	heartRate	filter
	perfusionIndex	filterDecimal
	sphb	filterDecimal
	spoc	filterDecimal
	desat	filter
	pidelta	filter
	pvi	filter
	alarm	filter
	alarm1	filter
	acsAlarm	filter
	exc	filter
	exc1	filter
	exc2	filter
	acsExc	filter
	eegPSI	filter
	eegEMG	filterDecimal
	eegSR	filterDecimal
	eegSEFL	filterDecimal
	eegSEFR	filterDecimal
	eegARTF	filterDecimal
	eegALARM	filter
	eegEXC	filter

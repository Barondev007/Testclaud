/**
 * Build the response for KVM refresh operation
 */

var status = context.getVariable('kvm.refreshStatus');
var mapName = context.getVariable('kvm.refreshed.mapName');
var keyName = context.getVariable('kvm.refreshed.keyName');
var putStatusCode = context.getVariable('mgmtPutResponse.status.code');

var response = {
    success: false,
    kvmName: mapName,
    keyName: keyName,
    message: '',
    timestamp: new Date().toISOString()
};

if (status === 'ready' && putStatusCode == 200) {
    response.success = true;
    response.message = 'KVM cache refreshed successfully. Entry \'' + keyName + '\' in KVM \'' + mapName + '\' has been invalidated and reloaded.';
} else if (status === 'ready_bulk') {
    response.success = true;
    response.message = 'KVM \'' + mapName + '\' retrieved. Note: Bulk refresh requires refreshing each entry individually.';
    response.entryCount = context.getVariable('kvm.entryCount');
} else if (status === 'empty') {
    response.success = false;
    response.message = 'KVM \'' + mapName + '\' is empty or key \'' + keyName + '\' not found.';
} else {
    response.success = false;
    response.message = context.getVariable('kvm.refreshError') || 'Unknown error occurred';
    response.mgmtApiStatus = context.getVariable('mgmtApiResponse.status.code');
}

context.setVariable('response.content', JSON.stringify(response, null, 2));
context.setVariable('response.header.Content-Type', 'application/json');

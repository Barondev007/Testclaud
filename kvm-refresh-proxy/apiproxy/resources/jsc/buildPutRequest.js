/**
 * Parse GET response and prepare PUT request to refresh KVM cache
 */

var response = context.getVariable('mgmtApiResponse.content');
var statusCode = context.getVariable('mgmtApiResponse.status.code');

if (statusCode == 200 && response) {
    try {
        var kvmEntry = JSON.parse(response);

        // For single entry refresh
        if (kvmEntry.name && kvmEntry.value !== undefined) {
            context.setVariable('kvm.putPayload', JSON.stringify({
                name: kvmEntry.name,
                value: kvmEntry.value
            }));
            context.setVariable('kvm.refreshStatus', 'ready');
        }
        // For full KVM refresh (list of entries)
        else if (kvmEntry.entry) {
            context.setVariable('kvm.entries', JSON.stringify(kvmEntry.entry));
            context.setVariable('kvm.entryCount', kvmEntry.entry.length);
            context.setVariable('kvm.refreshStatus', 'ready_bulk');
        }
        else {
            context.setVariable('kvm.refreshStatus', 'empty');
        }
    } catch (e) {
        context.setVariable('kvm.refreshStatus', 'error');
        context.setVariable('kvm.refreshError', 'Failed to parse response: ' + e.message);
    }
} else {
    context.setVariable('kvm.refreshStatus', 'error');
    context.setVariable('kvm.refreshError', 'Management API returned: ' + statusCode);
}

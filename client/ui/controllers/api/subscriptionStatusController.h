#ifndef SUBSCRIPTIONSTATUSCONTROLLER_H
#define SUBSCRIPTIONSTATUSCONTROLLER_H

#include <QDateTime>
#include <QObject>
#include <QTimer>

#include "settings.h"
#include "ui/models/servers_model.h"

// Periodically checks the remaining validity of the currently selected server's subscription
// against the LeninVPN subscription backend, identified by the client's AmneziaWG public key.
class SubscriptionStatusController : public QObject
{
    Q_OBJECT

public:
    explicit SubscriptionStatusController(const QSharedPointer<ServersModel> &serversModel, const std::shared_ptr<Settings> &settings,
                                          QObject *parent = nullptr);

    Q_PROPERTY(bool isAvailable READ isAvailable NOTIFY statusChanged)
    Q_PROPERTY(bool isActive READ isActive NOTIFY statusChanged)
    Q_PROPERTY(int daysRemaining READ daysRemaining NOTIFY statusChanged)
    Q_PROPERTY(QString expiresAtText READ expiresAtText NOTIFY statusChanged)
    Q_PROPERTY(QString subscribeUrl READ subscribeUrl CONSTANT)
    // True when the subscription has expired or has less than 24 hours left before it expires.
    Q_PROPERTY(bool isExpiringSoon READ isExpiringSoon NOTIFY statusChanged)

    bool isAvailable() const;
    bool isActive() const;
    int daysRemaining() const;
    QString expiresAtText() const;
    QString subscribeUrl() const;
    bool isExpiringSoon() const;

public slots:
    void refreshStatus();

signals:
    void statusChanged();

private:
    QString extractClientPubKey() const;

    QSharedPointer<ServersModel> m_serversModel;
    std::shared_ptr<Settings> m_settings;
    QTimer m_refreshTimer;

    QString m_lastRequestedPubKey;

    bool m_isAvailable = false;
    bool m_isActive = false;
    int m_daysRemaining = 0;
    QDateTime m_expiresAt;
};

#endif // SUBSCRIPTIONSTATUSCONTROLLER_H

sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update -y
sudo apt-get install -y ansible=2.0.2.0-1ppa~trusty

ansible --version
ansible-playbook --version
